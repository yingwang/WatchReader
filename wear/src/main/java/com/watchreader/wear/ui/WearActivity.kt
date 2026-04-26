package com.watchreader.wear.ui

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.watchreader.wear.ui.navigation.WearNavigation
import com.watchreader.wear.ui.theme.WatchReaderWearTheme
import kotlinx.coroutines.flow.MutableSharedFlow

class WearActivity : ComponentActivity() {

    /** Rotary crown events emitted as AXIS_SCROLL delta values. */
    val rotaryEvents = MutableSharedFlow<Float>(extraBufferCapacity = 16)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.source and InputDevice.SOURCE_ROTARY_ENCODER != 0
        ) {
            rotaryEvents.tryEmit(event.getAxisValue(MotionEvent.AXIS_SCROLL))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchReaderWearTheme {
                WearNavigation()
            }
        }
    }
}
