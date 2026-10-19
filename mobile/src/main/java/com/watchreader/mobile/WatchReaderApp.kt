package com.watchreader.mobile

import android.app.Application
import android.util.Log
import com.watchreader.shared.bookmark.BookmarkRepository
import com.watchreader.shared.ProgressDataSync
import com.watchreader.shared.stats.ReadingStatsSync
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.service.BookTransferWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "WatchReader"

class WatchReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BookRepository.init(this)
        BookmarkRepository.get(this).requestSync()
        // Housekeeping that fails (a full disk, a damaged database) is logged, never allowed to
        // become a crash on every launch.
        val quietly = CoroutineExceptionHandler { _, e -> Log.w(TAG, "Start-up housekeeping failed", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + quietly).launch {
            runCatching { BookRepository.resetSyncAfterRestore(this@WatchReaderApp) }.onFailure { Log.w(TAG, "Could not check for a restored backup", it) }
            // Interrupted sends return to the durable queue after process restart.
            runCatching { BookRepository.recoverStaleTransfers() }.onFailure { Log.w(TAG, "Could not recover stale transfers", it) }
            runCatching { BookTransferWorker.resumeWaiting(this@WatchReaderApp) }
                .onFailure { Log.w(TAG, "Could not resume book transfers", it) }
            runCatching { BookRepository.seedSampleIfNeeded(this@WatchReaderApp) }.onFailure { Log.w(TAG, "Could not seed the sample book", it) }
            runCatching { ProgressDataSync.restore(this@WatchReaderApp, BookRepository::applyProgress) }
                .onFailure { Log.w(TAG, "Could not restore synced progress", it) }
            runCatching { ReadingStatsSync.restore(this@WatchReaderApp, BookRepository::applyWatchReport) }
                .onFailure { Log.w(TAG, "Could not restore the watch's reading time", it) }
        }
    }
}
