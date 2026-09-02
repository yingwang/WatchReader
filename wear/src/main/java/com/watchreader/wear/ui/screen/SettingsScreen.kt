package com.watchreader.wear.ui.screen

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchreader.wear.BuildConfig
import com.watchreader.wear.R
import com.watchreader.wear.settings.ReaderPrefs
import com.watchreader.wear.reader.Typefaces
import com.watchreader.wear.settings.ReaderTheme
import com.watchreader.wear.tts.TtsLanguages
import com.watchreader.wear.ui.theme.DimText
import com.watchreader.wear.ui.theme.WarmAmber
import com.watchreader.wear.ui.theme.WarmWhite

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { ReaderPrefs(context) }
    val listState = rememberScalingLazyListState()
    val view = LocalView.current

    var fontSize by remember { mutableIntStateOf(prefs.fontSize) }
    var speechRate by remember { mutableFloatStateOf(prefs.speechRate) }
    var fontFamily by remember { mutableStateOf(prefs.fontFamily) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var keepScreenOn by remember { mutableStateOf(prefs.keepScreenOn) }
    // Which of the two reading languages this watch can actually speak, or null until asked.
    var voices by remember { mutableStateOf<TtsLanguages.Availability?>(null) }

    DisposableEffect(Unit) {
        val engine = TtsLanguages.probe(context) { voices = it }
        onDispose { engine.shutdown() }
    }

    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Text(stringResource(R.string.settings_title), fontSize = 14.sp, color = WarmAmber) }

            item { Text(stringResource(R.string.settings_font_size, fontSize), color = WarmWhite, fontSize = 12.sp) }
            item {
                InlineSlider(
                    value = fontSize.toFloat(),
                    onValueChange = {
                        fontSize = it.toInt()
                        prefs.fontSize = fontSize
                        tick()
                    },
                    valueRange = ReaderPrefs.MIN_FONT.toFloat()..ReaderPrefs.MAX_FONT.toFloat(),
                    steps = ReaderPrefs.MAX_FONT - ReaderPrefs.MIN_FONT - 1,
                    increaseIcon = { Text("+", color = WarmWhite, fontSize = 18.sp) },
                    decreaseIcon = { Text("–", color = WarmWhite, fontSize = 18.sp) },
                    modifier = Modifier.fillMaxWidth(0.85f),
                )
            }

            item {
                CycleRow(stringResource(R.string.settings_typeface), Typefaces.labelFor(fontFamily)) {
                    val faces = Typefaces.available()
                    val idx = faces.indexOfFirst { it.key == fontFamily }
                    fontFamily = faces[(idx + 1) % faces.size].key
                    prefs.fontFamily = fontFamily
                    tick()
                }
            }

            item {
                val label = stringResource(if (theme == ReaderTheme.DARK) R.string.settings_theme_dark else R.string.settings_theme_sepia)
                CycleRow(stringResource(R.string.settings_theme), label) {
                    theme = if (theme == ReaderTheme.DARK) ReaderTheme.SEPIA else ReaderTheme.DARK
                    prefs.theme = theme
                    tick()
                }
            }

            item {
                ToggleChip(
                    checked = keepScreenOn,
                    onCheckedChange = {
                        keepScreenOn = it
                        prefs.keepScreenOn = it
                        tick()
                    },
                    label = { Text(stringResource(R.string.settings_keep_screen_on), fontSize = 11.sp) },
                    toggleControl = { Switch(checked = keepScreenOn) },
                    colors = ToggleChipDefaults.toggleChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f).padding(top = 6.dp),
                )
            }

            item {
                Text(
                    stringResource(R.string.settings_speech_rate, String.format(java.util.Locale.US, "%.1f", speechRate)),
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
                        prefs.speechRate = it
                        tick()
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    increaseIcon = { Text("+", color = WarmWhite, fontSize = 18.sp) },
                    decreaseIcon = { Text("–", color = WarmWhite, fontSize = 18.sp) },
                    modifier = Modifier.fillMaxWidth(0.85f),
                )
            }

            voices?.let { v ->
                item {
                    // The voice is never chosen by hand: each sentence is spoken in its own
                    // language. All this line does is say whether the watch has that voice.
                    Text(
                        text = if (v.installed.isEmpty()) {
                            stringResource(R.string.settings_voices_none)
                        } else {
                            stringResource(
                                R.string.settings_voices_have,
                                v.installed.joinToString("  ") { TtsLanguages.label(it) },
                            )
                        },
                        color = WarmWhite,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.9f).padding(top = 10.dp),
                    )
                }
                if (v.missing.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.settings_voices_missing,
                                v.missing.joinToString("  ") { TtsLanguages.label(it) },
                            ),
                            color = DimText,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(0.9f).padding(top = 2.dp),
                        )
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    color = DimText,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CycleRow(name: String, value: String, onClick: () -> Unit) {
    Text(
        text = "$name: $value  ▶",
        color = WarmWhite,
        fontSize = 12.sp,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable(onClick = onClick),
    )
}
