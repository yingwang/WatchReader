package com.watchreader.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.watchreader.wear.ui.navigation.WearNavigation
import com.watchreader.wear.ui.theme.WatchReaderWearTheme

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchReaderWearTheme {
                WearNavigation()
            }
        }
    }
}
