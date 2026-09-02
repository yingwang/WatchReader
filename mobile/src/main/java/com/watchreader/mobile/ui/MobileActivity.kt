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
        if (savedInstanceState == null && SharedIntent.capture(intent)) shareGeneration++
        setContent {
            WatchReaderTheme {
                MobileNavigation(shareGeneration = shareGeneration)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (SharedIntent.capture(intent)) shareGeneration++
    }
}
