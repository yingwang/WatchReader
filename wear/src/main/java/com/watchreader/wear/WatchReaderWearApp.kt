package com.watchreader.wear

import android.app.Application
import com.watchreader.wear.data.repository.WearBookRepository
import com.watchreader.wear.service.TtsService

class WatchReaderWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WearBookRepository.init(this)
        TtsService.ensureNotificationChannel(this)
    }
}
