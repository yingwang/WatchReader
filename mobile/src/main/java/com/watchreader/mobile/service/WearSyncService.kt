package com.watchreader.mobile.service

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.shared.BookReceipt
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ListenerGuard
import com.watchreader.shared.ReadingProgress
import com.watchreader.shared.PendingDeletes
import com.watchreader.shared.ProgressDataSync
import com.watchreader.shared.stats.ReadingStatsSync
import com.watchreader.shared.bookmark.BookmarkRepository
import com.watchreader.shared.bookmark.BookmarkSync
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

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name == DataLayerPaths.WEAR_CAPABILITY && info.nodes.isNotEmpty()) wakeTransfers()
    }

    private fun wakeTransfers() {
        BookmarkRepository.get(this).requestSync()
        runBlocking {
            runCatching { BookTransferWorker.resumeWaiting(this@WearSyncService, reconnect = true) }
                .onFailure { Log.w(TAG, "Could not resume pending transfers", it) }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        runBlocking {
            // A bookmark file the app will not read or a full database fails that one event; the
            // rest of the batch is still applied, and the process stays up (see ListenerGuard).
            ListenerGuard.each(events, { event, e -> Log.w(TAG, "Could not apply ${pathOf(event)}", e) }) { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return@each
                val item = event.dataItem
                BookmarkSync.receive(this@WearSyncService, item.uri.path, item.data)
                ProgressDataSync.decode(item.uri.path, item.data)?.let { BookRepository.applyProgress(it) }
                ReadingStatsSync.decode(item.uri.path, item.data)?.let { BookRepository.applyWatchReport(it) }
            }
        }
    }

    private fun pathOf(event: DataEvent): String? = runCatching { event.dataItem.uri.path }.getOrNull()

    override fun onMessageReceived(messageEvent: MessageEvent) {
        ListenerGuard.guard({ Log.w(TAG, "Could not handle ${messageEvent.path}", it) }) { handle(messageEvent) }
    }

    private fun handle(messageEvent: MessageEvent) {
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
                        // A late rejection must not overwrite a successful receipt or a cancelled queue.
                        val next = if (receipt.retryable) SyncStatus.QUEUED else SyncStatus.FAILED
                        if (!BookRepository.changeSyncStatus(receipt.bookId, SyncStatus.SENDING, next, receipt.message)) {
                            BookRepository.changeSyncStatus(receipt.bookId, SyncStatus.QUEUED, next, receipt.message)
                        }
                    }
                }
            }
            DataLayerPaths.BOOK_REMOVED_PATH -> {
                if (payload.isNotBlank()) {
                    runBlocking {
                        BookRepository.updateSyncStatus(payload, SyncStatus.NOT_SENT)
                        // A delete the phone left for the watch has been carried out.
                        runCatching { PendingDeletes.clear(this@WearSyncService, payload) }
                    }
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
