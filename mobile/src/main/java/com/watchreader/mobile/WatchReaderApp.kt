package com.watchreader.mobile

import android.app.Application
import android.util.Log
import com.watchreader.mobile.data.repository.BookRepository
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
        // Housekeeping that fails (a full disk, a damaged database) is logged, never allowed to
        // become a crash on every launch.
        val quietly = CoroutineExceptionHandler { _, e -> Log.w(TAG, "Start-up housekeeping failed", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + quietly).launch {
            // Nothing survives a process restart to wait for a receipt, so whatever was mid-send
            // when the process went is stuck until it is marked failed here.
            runCatching { BookRepository.recoverStaleTransfers() }.onFailure { Log.w(TAG, "Could not recover stale transfers", it) }
            runCatching { BookRepository.seedSampleIfNeeded(this@WatchReaderApp) }.onFailure { Log.w(TAG, "Could not seed the sample book", it) }
        }
    }
}
