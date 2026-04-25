package com.watchreader.mobile.service

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class WearSyncService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            DataLayerPaths.PONG_PATH -> {
                val json = JSONObject(String(messageEvent.data))
                val bookId = json.getString("bookId")
                val status = json.getString("status")
                scope.launch {
                    val syncStatus = if (status == "ok") SyncStatus.SENT else SyncStatus.FAILED
                    BookRepository.updateSyncStatus(bookId, syncStatus)
                }
            }
            DataLayerPaths.PROGRESS_PATH -> {
                val progress = ReadingProgress.fromJson(String(messageEvent.data))
                scope.launch {
                    BookRepository.updateProgress(progress.bookId, progress.percentage)
                }
            }
        }
    }
}
