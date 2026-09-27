package com.watchreader.mobile.service

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.shared.BookReceipt
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ReadingProgress
import com.watchreader.shared.ProgressDataSync
import kotlinx.coroutines.runBlocking

private const val TAG = "WatchReader"

/**
 * Receives the watch's receipts, deletions and reading progress.
 *
 * Each message is written to the database before the callback returns. The system may unbind and
 * destroy this service as soon as a callback is done, so work handed to a scope that is cancelled
 * in onDestroy() can be dropped on the floor; a dropped receipt leaves a book the watch has
 * marked failed once the sender's wait runs out. The callbacks arrive on a background thread and
 * every write here is a single-row update, so blocking on them is safe and short; so is the one
 * message a receipt can send back, the contents of a book too long for its header.
 */
class WearSyncService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        runBlocking {
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                ProgressDataSync.decode(item.uri.path, item.data)?.let { BookRepository.applyProgress(it) }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val payload = String(messageEvent.data, Charsets.UTF_8)
        when (messageEvent.path) {
            DataLayerPaths.BOOK_RECEIVED_PATH -> {
                val receipt = runCatching { BookReceipt.fromJson(payload) }.getOrElse {
                    Log.w(TAG, "Bad receipt from watch: $payload")
                    return
                }
                runBlocking {
                    if (receipt.ok) {
                        BookRepository.updateSyncStatus(receipt.bookId, SyncStatus.SENT)
                        // A long contents list could not ride in the book's header; it follows now.
                        BookRepository.getById(receipt.bookId)?.let {
                            BookSender(this@WearSyncService).sendContentsIfLeftOut(it, messageEvent.sourceNodeId)
                        }
                    } else {
                        BookRepository.updateSyncStatus(receipt.bookId, SyncStatus.FAILED, receipt.message)
                    }
                }
            }
            DataLayerPaths.BOOK_REMOVED_PATH -> {
                if (payload.isNotBlank()) {
                    runBlocking { BookRepository.updateSyncStatus(payload, SyncStatus.NOT_SENT) }
                }
            }
            DataLayerPaths.PROGRESS_PATH -> {
                val progress = runCatching { ReadingProgress.fromJson(payload) }.getOrElse {
                    Log.w(TAG, "Bad progress from watch: $payload")
                    return
                }
                runBlocking { BookRepository.applyProgress(progress) }
            }
        }
    }
}
