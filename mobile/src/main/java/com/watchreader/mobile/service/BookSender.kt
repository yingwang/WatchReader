package com.watchreader.mobile.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.watchreader.mobile.data.model.Book
import com.watchreader.shared.BookMetadata
import com.watchreader.shared.BookTransfer
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WatchReader"

/** Which watch, if any, can take a book right now. */
sealed class WatchLookup {
    /** A reachable watch that runs WatchReader. */
    data class Ready(val nodeId: String, val name: String) : WatchLookup()

    /** A watch is paired and reachable but has no WatchReader on it. */
    data class WithoutApp(val nodeId: String, val name: String) : WatchLookup()

    /** No watch reachable at all. */
    object None : WatchLookup()
}

class BookSender(private val context: Context) {

    /**
     * Prefers a node advertising the watch app's capability; a plain "first connected node"
     * would happily pick a second watch, or a node that never installed the app.
     */
    suspend fun findWatch(): WatchLookup {
        val capable = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(DataLayerPaths.WEAR_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await().nodes
        }.getOrElse { emptySet() }
        pick(capable)?.let { return WatchLookup.Ready(it.id, it.displayName) }

        val connected = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrElse { emptyList() }
        pick(connected.toSet())?.let { return WatchLookup.WithoutApp(it.id, it.displayName) }
        return WatchLookup.None
    }

    private fun pick(nodes: Set<Node>): Node? = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()

    /**
     * Streams the book over one channel: metadata header, newline, UTF-8 text. Returns once the
     * bytes are handed to the Data Layer; the watch confirms storage with a BOOK_RECEIVED message.
     */
    suspend fun sendBook(book: Book, nodeId: String): Boolean = withContext(Dispatchers.IO) {
        val channelClient = Wearable.getChannelClient(context)
        var channel: ChannelClient.Channel? = null
        try {
            channel = channelClient.openChannel(nodeId, DataLayerPaths.bookChannelPath(book.id)).await()
            val output = channelClient.getOutputStream(channel).await()
            val file = File(book.filePath)
            val meta = BookMetadata(
                id = book.id,
                title = book.title,
                sizeBytes = file.length(),
                addedEpochMs = book.addedEpochMs,
                totalChars = book.totalChars,
            )
            output.use { out ->
                BookTransfer.writeHeader(out, meta)
                file.inputStream().use { input -> input.copyTo(out, bufferSize = 16 * 1024) }
                out.flush()
            }
            Log.d(TAG, "Streamed '${book.title}' (${file.length()} bytes) to $nodeId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sending '${book.title}' failed", e)
            false
        } finally {
            channel?.let { runCatching { channelClient.close(it).await() } }
        }
    }

    suspend fun deleteBookOnWatch(bookId: String, nodeId: String) {
        runCatching {
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, DataLayerPaths.DELETE_BOOK_PATH, bookId.toByteArray(Charsets.UTF_8))
                .await()
        }.onFailure { Log.w(TAG, "Could not tell the watch to delete $bookId", it) }
    }

    /** Tells the watch where the reader on the phone got to. Silent when no watch is around. */
    suspend fun sendProgress(progress: ReadingProgress) {
        val watch = findWatch() as? WatchLookup.Ready ?: return
        runCatching {
            Wearable.getMessageClient(context)
                .sendMessage(
                    watch.nodeId,
                    DataLayerPaths.PROGRESS_PATH,
                    progress.toJson().toByteArray(Charsets.UTF_8),
                )
                .await()
        }.onFailure { Log.w(TAG, "Could not send progress to the watch", it) }
    }

    /** Opens this app's Play listing on the watch so the user can install it there. */
    suspend fun openPlayStoreOnWatch(nodeId: String): Boolean = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse("market://details?id=${context.packageName}"))
        runCatching {
            RemoteActivityHelper(context).startRemoteActivity(intent, nodeId).get()
            true
        }.getOrElse {
            Log.w(TAG, "Could not open Play on the watch", it)
            false
        }
    }
}
