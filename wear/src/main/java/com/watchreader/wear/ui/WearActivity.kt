package com.watchreader.wear.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.watchreader.wear.ui.navigation.WearNavigation
import com.watchreader.wear.ui.theme.WatchReaderWearTheme

class WearActivity : ComponentActivity() {
    /** Book to open straight away, e.g. from the read-aloud notification; consumed by navigation. */
    private var openRequest by mutableStateOf<OpenRequest?>(null)

    class OpenRequest(val bookId: String, val serial: Long = System.nanoTime())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_BOOK_ID)?.let { openRequest = OpenRequest(it) }
        setContent {
            WatchReaderWearTheme {
                WearNavigation(openRequest = openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_BOOK_ID)?.let { openRequest = OpenRequest(it) }
    }

    companion object {
        const val EXTRA_BOOK_ID = "open_book_id"
    }
}
