package com.watchreader.wear.service

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.watchreader.shared.BookContents
import com.watchreader.shared.BookReceipt
import com.watchreader.shared.BookTransfer
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ReadingProgress
import com.watchreader.shared.PendingDeletes
import com.watchreader.shared.ProgressDataSync
import com.watchreader.shared.resentBookOffset
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File

private const val TAG = "WatchReader"

/**
 * The one and only receiver on the watch. Books arrive on a channel whose path names the book;
 * the stream carries its own metadata header, so nothing has to be remembered between callbacks.
 * The copy runs inside the callback on purpose: the service is kept alive for exactly that long.
 */
class BookReceiverService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        runBlocking {
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                ProgressDataSync.decode(item.uri.path, item.data)?.let { WearBookRepository.applyProgressFromPhone(it) }
                PendingDeletes.bookId(item.uri.path, item.data)?.let { id ->
                    TtsService.stopIfPlaying(this@BookReceiverService, id)
                    WearBookRepository.deleteForPhone(id)
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            DataLayerPaths.DELETE_BOOK_PATH -> {
                val bookId = String(messageEvent.data, Charsets.UTF_8)
                if (bookId.isBlank()) return
                TtsService.stopIfPlaying(this, bookId)
                runBlocking { WearBookRepository.delete(bookId, tellPhone = false) }
            }
            DataLayerPaths.PROGRESS_PATH -> {
                val progress = runCatching {
                    ReadingProgress.fromJson(String(messageEvent.data, Charsets.UTF_8))
                }.getOrElse {
                    Log.w(TAG, "Bad progress from the phone")
                    return
                }
                runBlocking { WearBookRepository.applyProgressFromPhone(progress) }
            }
            DataLayerPaths.CONTENTS_PATH -> {
                val contents = runCatching {
                    BookContents.fromJson(String(messageEvent.data, Charsets.UTF_8))
                }.getOrElse {
                    Log.w(TAG, "Bad contents from the phone")
                    return
                }
                // Contents for a book no longer here have nothing to go beside.
                val book = runBlocking { WearBookRepository.getById(contents.bookId) } ?: return
                runCatching { WearBookRepository.storeContents(File(book.filePath), contents.tocJson) }
                    .onFailure { Log.w(TAG, "Could not store the contents of ${contents.bookId}", it) }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val bookId = DataLayerPaths.bookIdFromChannelPath(channel.path) ?: return
        val client = Wearable.getChannelClient(this)
        val tmp = File(WearBookRepository.getBooksDir(), "$bookId.part")
        val receipt = try {
            val input = BufferedInputStream(Tasks.await(client.getInputStream(channel)), 32 * 1024)
            val meta = input.use { stream ->
                val header = BookTransfer.readHeader(stream)
                tmp.outputStream().use { out -> stream.copyTo(out, bufferSize = 32 * 1024) }
                header
            }
            if (meta.id != bookId) throw IllegalStateException("Header is for ${meta.id}, channel is for $bookId")
            val text = tmp.readText(Charsets.UTF_8)
            if (text.isBlank()) throw IllegalStateException("Received an empty book")
            val file = File(WearBookRepository.getBooksDir(), "$bookId.txt")
            if (!tmp.renameTo(file)) throw IllegalStateException("Could not store the book")
            WearBookRepository.storeContents(file, meta.tocJson)
            runBlocking {
                val existing = WearBookRepository.getById(bookId)
                val stored = WearBook(
                    id = meta.id,
                    title = meta.title,
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                    addedEpochMs = meta.addedEpochMs,
                    totalChars = text.length,
                    // a re-sent book keeps its place, moved in proportion if the text changed length
                    readOffsetChars = resentBookOffset(existing?.readOffsetChars, existing?.totalChars, text.length),
                    lastReadEpochMs = existing?.lastReadEpochMs ?: 0,
                )
                WearBookRepository.insert(stored)
                // The kept copy still holds the old offset under the same stamp, and the next
                // start would put it back; replace it with the moved one.
                if (existing != null && existing.totalChars != text.length && existing.lastReadEpochMs > 0) {
                    WearBookRepository.republishProgress(stored)
                }
                if (existing == null) {
                    runCatching {
                        ProgressDataSync.restore(this@BookReceiverService, WearBookRepository::applyProgressFromPhone)
                    }.onFailure { Log.w(TAG, "Could not restore synced progress", it) }
                }
            }
            Log.d(TAG, "Stored '${meta.title}' (${text.length} chars)")
            BookReceipt(bookId, ok = true, totalChars = text.length)
        } catch (e: Exception) {
            Log.e(TAG, "Receiving $bookId failed", e)
            tmp.delete()
            BookReceipt(bookId, ok = false, message = e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { Tasks.await(client.close(channel)) }
        }
        runCatching {
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(
                    channel.nodeId,
                    DataLayerPaths.BOOK_RECEIVED_PATH,
                    receipt.toJson().toByteArray(Charsets.UTF_8),
                ),
            )
        }.onFailure { Log.w(TAG, "Could not send the receipt for $bookId", it) }
    }
}
