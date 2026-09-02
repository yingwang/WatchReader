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
            BookRepository.seedSampleIfNeeded(this@WatchReaderApp)
        }
    }
}
