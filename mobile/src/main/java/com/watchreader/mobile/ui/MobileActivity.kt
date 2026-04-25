package com.watchreader.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.watchreader.mobile.ui.navigation.MobileNavigation
import com.watchreader.mobile.ui.theme.WatchReaderTheme

class MobileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchReaderTheme {
                MobileNavigation()
            }
        }
    }
}
