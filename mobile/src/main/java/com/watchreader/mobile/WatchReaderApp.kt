package com.watchreader.mobile

import android.app.Application
import com.watchreader.mobile.data.repository.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WatchReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BookRepository.init(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Nothing survives a process restart to wait for a receipt, so whatever was mid-send
            // when the process went is stuck until it is marked failed here.
            BookRepository.recoverStaleTransfers()
            BookRepository.seedSampleIfNeeded(this@WatchReaderApp)
        }
    }
}
