package com.watchreader.mobile

import android.app.Application
import com.watchreader.mobile.data.repository.BookRepository

class WatchReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BookRepository.init(this)
    }
}
