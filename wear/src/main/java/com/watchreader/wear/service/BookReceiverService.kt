package com.watchreader.wear.service

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.watchreader.shared.BookMetadata
import com.watchreader.shared.DataLayerPaths
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class BookReceiverService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingMetadata: BookMetadata? = null

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            DataLayerPaths.BOOK_METADATA_PATH -> {
                pendingMetadata = BookMetadata.fromJson(String(messageEvent.data))
            }
            DataLayerPaths.DELETE_BOOK_PATH -> {
                val bookId = String(messageEvent.data)
                scope.launch { WearBookRepository.delete(bookId) }
            }
            DataLayerPaths.PING_PATH -> {
                Wearable.getMessageClient(this).sendMessage(
                    messageEvent.sourceNodeId,
                    DataLayerPaths.PONG_PATH,
                    byteArrayOf(),
                )
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val meta = pendingMetadata ?: return
        pendingMetadata = null

        scope.launch {
            try {
                val file = File(WearBookRepository.getBooksDir(), "${meta.id}.txt")

                val channelClient = Wearable.getChannelClient(this@BookReceiverService)
                val inputStream = Tasks.await(channelClient.getInputStream(channel))

                file.outputStream().use { output ->
                    inputStream.copyTo(output, bufferSize = 8192)
                }

                val totalChars = file.readText().length

                val wearBook = WearBook(
                    id = meta.id,
                    title = meta.title,
                    filePath = file.absolutePath,
                    sizeBytes = meta.sizeBytes,
                    addedEpochMs = meta.addedEpochMs,
                    totalChars = totalChars,
                )
                WearBookRepository.insert(wearBook)

                // ACK success
                Wearable.getMessageClient(this@BookReceiverService).sendMessage(
                    channel.nodeId,
                    DataLayerPaths.PONG_PATH,
                    """{"bookId":"${meta.id}","status":"ok"}""".toByteArray(),
                )
            } catch (e: Exception) {
                // ACK failure
                Wearable.getMessageClient(this@BookReceiverService).sendMessage(
                    channel.nodeId,
                    DataLayerPaths.PONG_PATH,
                    """{"bookId":"${meta.id}","status":"error","message":"${e.message}"}""".toByteArray(),
                )
            }
        }
    }
}
