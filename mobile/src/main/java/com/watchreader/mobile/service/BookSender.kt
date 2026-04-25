package com.watchreader.mobile.service

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.watchreader.mobile.data.model.Book
import com.watchreader.shared.BookMetadata
import com.watchreader.shared.DataLayerPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WatchReader"

class BookSender(private val context: Context) {

    suspend fun findWatchNodeId(): String? {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        Log.d(TAG, "Connected nodes: ${nodes.map { "${it.displayName}(${it.id})" }}")
        return nodes.firstOrNull()?.id
    }

    suspend fun sendBook(book: Book, nodeId: String): Boolean = withContext(Dispatchers.IO) {
        val messageClient = Wearable.getMessageClient(context)
        val channelClient = Wearable.getChannelClient(context)

        // Step 1: Send metadata
        val meta = BookMetadata(
            id = book.id,
            title = book.title,
            sizeBytes = book.sizeBytes,
            addedEpochMs = book.addedEpochMs,
        )
        Log.d(TAG, "Step 1: Sending metadata for '${book.title}'")
        messageClient.sendMessage(
            nodeId,
            DataLayerPaths.BOOK_METADATA_PATH,
            meta.toJson().toByteArray(),
        ).await()
        Log.d(TAG, "Step 1: Metadata sent")

        // Small delay to let the watch service wake up and process metadata
        kotlinx.coroutines.delay(500)

        // Step 2: Stream file via ChannelClient
        var channel: ChannelClient.Channel? = null
        try {
            Log.d(TAG, "Step 2: Opening channel to $nodeId")
            channel = channelClient.openChannel(nodeId, DataLayerPaths.BOOK_ASSET_PATH).await()
            Log.d(TAG, "Step 2: Channel opened, getting output stream")
            val outputStream = channelClient.getOutputStream(channel).await()
            val file = File(book.filePath)
            Log.d(TAG, "Step 2: Streaming ${file.length()} bytes")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var total = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    total += bytesRead
                }
                Log.d(TAG, "Step 2: Streamed $total bytes")
            }
            outputStream.close()
            Log.d(TAG, "Step 2: Done, closing channel")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Step 2: Channel error", e)
            false
        } finally {
            channel?.let {
                try { channelClient.close(it).await() } catch (_: Exception) {}
            }
        }
    }

    suspend fun deleteBookOnWatch(bookId: String, nodeId: String) {
        Wearable.getMessageClient(context).sendMessage(
            nodeId,
            DataLayerPaths.DELETE_BOOK_PATH,
            bookId.toByteArray(),
        ).await()
    }
}
