package com.watchreader.wear

import android.app.Application
import com.watchreader.wear.data.repository.WearBookRepository
import com.watchreader.wear.service.TtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WatchReaderWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WearBookRepository.init(this)
        TtsService.ensureNotificationChannel(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            WearBookRepository.seedSampleIfNeeded()
        }
    }
}
