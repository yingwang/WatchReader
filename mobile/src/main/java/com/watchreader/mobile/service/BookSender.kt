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
import com.watchreader.shared.ProgressDataSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
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
        val client = Wearable.getChannelClient(context)
        var channel: ChannelClient.Channel? = null
        var callback: ChannelClient.ChannelCallback? = null
        val staged = File.createTempFile("book-transfer-", ".bin", context.cacheDir)
        try {
            staged.outputStream().use { out ->
                BookTransfer.writeHeader(out, metadata(book))
                File(book.filePath).inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
            }
            withTimeout(180_000) {
                val opened = client.openChannel(nodeId, DataLayerPaths.bookChannelPath(book.id)).await()
                channel = opened
                val closed = CompletableDeferred<Boolean>()
                val listener = object : ChannelClient.ChannelCallback() {
                    override fun onOutputClosed(channel: ChannelClient.Channel, reason: Int, error: Int) {
                        closed.complete(reason == CLOSE_REASON_NORMAL)
                    }
                    override fun onChannelClosed(channel: ChannelClient.Channel, reason: Int, error: Int) {
                        closed.complete(false)
                    }
                }
                callback = listener
                client.registerChannelCallback(opened, listener).await()
                client.sendFile(opened, Uri.fromFile(staged)).await()
                closed.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Book transfer timed out", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Book transfer failed", e)
            false
        } finally {
            withContext(NonCancellable) {
                channel?.let { opened ->
                    runCatching { withTimeout(10_000) { callback?.let { client.unregisterChannelCallback(opened, it).await() } } }
                    runCatching { withTimeout(10_000) { client.close(opened).await() } }
                }
                staged.delete()
            }
        }
    }

    /** The header a book travels under; worked out the same way when its receipt comes back. */
    private fun metadata(book: Book) = BookMetadata(
        id = book.id,
        title = book.title,
        sizeBytes = File(book.filePath).length(),
        addedEpochMs = book.addedEpochMs,
        totalChars = book.totalChars,
        tocJson = book.tocJson,
    )

    /**
     * Sends the contents of a book the watch has just confirmed, when they were too large to
     * travel in its header. Sent after the receipt rather than straight after the text, so the
     * watch already has the book to put them beside and a resend that is still being stored
     * cannot overwrite them with nothing.
     */
    suspend fun sendContentsIfLeftOut(book: Book, nodeId: String) = withContext(Dispatchers.IO) {
        val payload = BookTransfer.contentsLeftOut(metadata(book)) ?: return@withContext
        runCatching {
            Wearable.getMessageClient(context).sendMessage(nodeId, DataLayerPaths.CONTENTS_PATH, payload).await()
        }.onFailure { Log.w(TAG, "Could not send the contents of '${book.title}'", it) }
    }

    suspend fun deleteBookOnWatch(bookId: String, nodeId: String) {
        runCatching {
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, DataLayerPaths.DELETE_BOOK_PATH, bookId.toByteArray(Charsets.UTF_8))
                .await()
        }.onFailure { Log.w(TAG, "Could not tell the watch to delete $bookId", it) }
    }

    /** Persists progress in the data layer, for the watch to pick up when it next connects. */
    suspend fun publishProgress(progress: ReadingProgress) {
        runCatching { ProgressDataSync.publish(context, progress) }
            .onFailure { Log.w(TAG, "Could not persist progress for sync", it) }
    }

    /** Tells a watch in reach at once, after [publishProgress]; older watch apps hear only this. */
    suspend fun messageProgress(progress: ReadingProgress) {
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
