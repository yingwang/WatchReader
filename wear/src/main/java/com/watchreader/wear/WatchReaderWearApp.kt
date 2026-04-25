package com.watchreader.wear

import android.app.Application
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.watchreader.shared.BookMetadata
import com.watchreader.shared.DataLayerPaths
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "WatchReader"

class WatchReaderWearApp : Application(), MessageClient.OnMessageReceivedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingMetadata: BookMetadata? = null

    override fun onCreate() {
        super.onCreate()
        WearBookRepository.init(this)

        // Register listeners programmatically (more reliable than manifest)
        Log.d(TAG, "Registering message and channel listeners")
        Wearable.getMessageClient(this).addListener(this)
        Wearable.getChannelClient(this).registerChannelCallback(channelCallback)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "Message received: path=${messageEvent.path}, size=${messageEvent.data.size}")
        when (messageEvent.path) {
            DataLayerPaths.BOOK_METADATA_PATH -> {
                val meta = BookMetadata.fromJson(String(messageEvent.data))
                Log.d(TAG, "Got metadata: ${meta.title}, id=${meta.id}")
                pendingMetadata = meta
            }
            DataLayerPaths.DELETE_BOOK_PATH -> {
                val bookId = String(messageEvent.data)
                scope.launch { WearBookRepository.delete(bookId) }
            }
            DataLayerPaths.PING_PATH -> {
                Wearable.getMessageClient(this@WatchReaderWearApp).sendMessage(
                    messageEvent.sourceNodeId,
                    DataLayerPaths.PONG_PATH,
                    byteArrayOf(),
                )
            }
        }
    }

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            Log.d(TAG, "Channel opened: ${channel.path}")
            val meta = pendingMetadata ?: run {
                Log.e(TAG, "Channel opened but no pending metadata!")
                return
            }
            pendingMetadata = null

            scope.launch {
                try {
                    val file = File(WearBookRepository.getBooksDir(), "${meta.id}.txt")
                    Log.d(TAG, "Receiving book to: ${file.absolutePath}")

                    val channelClient = Wearable.getChannelClient(this@WatchReaderWearApp)
                    val inputStream = Tasks.await(channelClient.getInputStream(channel))

                    file.outputStream().use { output ->
                        val copied = inputStream.copyTo(output, bufferSize = 8192)
                        Log.d(TAG, "Received $copied bytes")
                    }

                    val text = file.readText()
                    val totalChars = text.length
                    Log.d(TAG, "Book has $totalChars chars")

                    val wearBook = WearBook(
                        id = meta.id,
                        title = meta.title,
                        filePath = file.absolutePath,
                        sizeBytes = meta.sizeBytes,
                        addedEpochMs = meta.addedEpochMs,
                        totalChars = totalChars,
                    )
                    WearBookRepository.insert(wearBook)
                    Log.d(TAG, "Book saved to database: ${meta.title}")

                    // ACK success
                    Wearable.getMessageClient(this@WatchReaderWearApp).sendMessage(
                        channel.nodeId,
                        DataLayerPaths.PONG_PATH,
                        """{"bookId":"${meta.id}","status":"ok"}""".toByteArray(),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error receiving book", e)
                }
            }
        }
    }
}
