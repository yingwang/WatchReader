package com.watchreader.wear.ui.screen

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberScalingLazyListState
import com.watchreader.wear.ui.theme.DimText
import com.watchreader.wear.ui.theme.WarmAmber
import com.watchreader.wear.ui.theme.WarmWhite
import kotlinx.coroutines.launch

private const val PREFS_NAME = "watchreader_settings"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_SPEECH_RATE = "speech_rate"

fun loadFontSize(context: Context): Int {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_FONT_SIZE, 16)
}

fun loadSpeechRate(context: Context): Float {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getFloat(KEY_SPEECH_RATE, 1.0f)
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current

    var fontSize by remember { mutableIntStateOf(loadFontSize(context)) }
    var speechRate by remember { mutableFloatStateOf(loadSpeechRate(context)) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun save() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FONT_SIZE, fontSize)
            .putFloat(KEY_SPEECH_RATE, speechRate)
            .apply()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                coroutineScope.launch { listState.scrollBy(event.verticalScrollPixels) }
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        Scaffold(
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        text = "Settings",
                        fontSize = 14.sp,
                        color = WarmAmber,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                // Font size
                item {
                    Text("Font Size: ${fontSize}sp", color = WarmWhite, fontSize = 12.sp)
                }
                item {
                    InlineSlider(
                        value = fontSize.toFloat(),
                        onValueChange = {
                            fontSize = it.toInt()
                            save()
                        },
                        valueRange = 12f..24f,
                        steps = 5,
                        increaseIcon = { InlineSliderDefaults.Increase },
                        decreaseIcon = { InlineSliderDefaults.Decrease },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Speech rate
                item {
                    Text(
                        "Speech Rate: ${String.format("%.1f", speechRate)}x",
                        color = WarmWhite,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item {
                    InlineSlider(
                        value = speechRate,
                        onValueChange = {
                            speechRate = it
                            save()
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        increaseIcon = { InlineSliderDefaults.Increase },
                        decreaseIcon = { InlineSliderDefaults.Decrease },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    Text(
                        "WatchReader v0.1.0",
                        color = DimText,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
