package com.watchreader.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.watchreader.mobile.ui.navigation.MobileNavigation
import com.watchreader.mobile.ui.theme.WatchReaderTheme

class MobileActivity : ComponentActivity() {
    /** Bumped whenever a new share arrives so the navigation reacts to it. */
    private var shareGeneration by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !fromRecents(intent) && SharedIntent.capture(intent)) shareGeneration++
        setContent {
            WatchReaderTheme {
                MobileNavigation(shareGeneration = shareGeneration)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!fromRecents(intent) && SharedIntent.capture(intent)) shareGeneration++
    }

    /**
     * Whether the task was brought back from Recents. The system then starts the activity with
     * the intent that first created the task, share and all, and taking it again would open the
     * add screen on a file the reader added long ago, or no longer has permission to read.
     */
    private fun fromRecents(intent: Intent?): Boolean =
        intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
}
