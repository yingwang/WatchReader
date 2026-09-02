package com.watchreader.mobile.service

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.shared.BookReceipt
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "WatchReader"

/** Receives the watch's receipts, deletions and reading progress. */
class WearSyncService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val payload = String(messageEvent.data, Charsets.UTF_8)
        when (messageEvent.path) {
            DataLayerPaths.BOOK_RECEIVED_PATH -> {
                val receipt = runCatching { BookReceipt.fromJson(payload) }.getOrElse {
                    Log.w(TAG, "Bad receipt from watch: $payload")
                    return
                }
                scope.launch {
                    BookRepository.updateSyncStatus(
                        receipt.bookId,
                        if (receipt.ok) SyncStatus.SENT else SyncStatus.FAILED,
                    )
                }
            }
            DataLayerPaths.BOOK_REMOVED_PATH -> {
                if (payload.isNotBlank()) {
                    scope.launch { BookRepository.updateSyncStatus(payload, SyncStatus.NOT_SENT) }
                }
            }
            DataLayerPaths.PROGRESS_PATH -> {
                val progress = runCatching { ReadingProgress.fromJson(payload) }.getOrElse {
                    Log.w(TAG, "Bad progress from watch: $payload")
                    return
                }
                scope.launch { BookRepository.updateProgress(progress.bookId, progress.percentage) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
