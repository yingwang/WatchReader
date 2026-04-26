package com.watchreader.wear.ui.screen

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.AutoCenteringParams
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberScalingLazyListState
import com.watchreader.wear.ui.WearActivity
import com.watchreader.wear.ui.theme.DimText
import com.watchreader.wear.ui.theme.WarmAmber
import com.watchreader.wear.ui.theme.WarmWhite
import kotlinx.coroutines.launch

private const val PREFS_NAME = "watchreader_settings"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_SPEECH_RATE = "speech_rate"
private const val KEY_FONT_FAMILY = "font_family"
private const val KEY_TTS_VOICE = "tts_voice"

fun loadFontSize(context: Context): Int =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_FONT_SIZE, 16)

fun loadSpeechRate(context: Context): Float =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_SPEECH_RATE, 1.0f)

fun loadFontFamily(context: Context): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FONT_FAMILY, "sans") ?: "sans"

fun loadTtsVoice(context: Context): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TTS_VOICE, "") ?: ""

private val FONT_OPTIONS = listOf(
    "sans" to "黑体",
    "serif" to "宋体",
    "kai" to "楷体",
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current

    var fontSize by remember { mutableIntStateOf(loadFontSize(context)) }
    var speechRate by remember { mutableFloatStateOf(loadSpeechRate(context)) }
    var fontFamily by remember { mutableStateOf(loadFontFamily(context)) }
    var ttsVoice by remember { mutableStateOf(loadTtsVoice(context)) }
    var voiceList by remember { mutableStateOf<List<String>>(emptyList()) }

    // Load TTS voices
    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Only pick voices that support Chinese or English
                val wantedLangs = setOf("zh", "en")
                voiceList = tts?.voices
                    ?.filter { v ->
                        !v.isNetworkConnectionRequired &&
                            v.locale.language in wantedLangs
                    }
                    ?.sortedWith(
                        compareBy<android.speech.tts.Voice> { if (it.locale.language == "zh") 0 else 1 }
                            .thenByDescending { it.quality }
                    )
                    ?.map { it.name }
                    ?.take(8)
                    ?: emptyList()
                    ?: emptyList()
            }
        }
        onDispose { tts?.shutdown() }
    }

    // Rotary crown via Activity
    val activity = context as? WearActivity
    LaunchedEffect(Unit) {
        activity?.rotaryEvents?.collect { delta ->
            coroutineScope.launch { listState.scrollBy(-delta * 40f) }
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun save() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FONT_SIZE, fontSize)
            .putFloat(KEY_SPEECH_RATE, speechRate)
            .putString(KEY_FONT_FAMILY, fontFamily)
            .putString(KEY_TTS_VOICE, ttsVoice)
            .apply()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                autoCentering = null,
            ) {
                item {
                    Text("Settings", fontSize = 14.sp, color = WarmAmber)
                }

                // Font size
                item {
                    Text("Font: ${fontSize}sp", color = WarmWhite, fontSize = 12.sp)
                }
                item {
                    InlineSlider(
                        value = fontSize.toFloat(),
                        onValueChange = {
                            fontSize = it.toInt()
                            save()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        },
                        valueRange = 12f..22f,
                        steps = 4,
                        increaseIcon = { Text("+", color = WarmWhite, fontSize = 18.sp) },
                        decreaseIcon = { Text("–", color = WarmWhite, fontSize = 18.sp) },
                        modifier = Modifier.fillMaxWidth(0.85f),
                    )
                }

                // Font family
                item {
                    val displayName = FONT_OPTIONS.find { it.first == fontFamily }?.second ?: "黑体"
                    Text(
                        text = "Typeface: $displayName  \u25B6",
                        color = WarmWhite,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable {
                                val idx = FONT_OPTIONS.indexOfFirst { it.first == fontFamily }
                                fontFamily = FONT_OPTIONS[(idx + 1) % FONT_OPTIONS.size].first
                                save()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            },
                    )
                }

                // Speech rate
                item {
                    Text(
                        "Speed: ${String.format("%.1f", speechRate)}x",
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
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        increaseIcon = { Text("+", color = WarmWhite, fontSize = 18.sp) },
                        decreaseIcon = { Text("–", color = WarmWhite, fontSize = 18.sp) },
                        modifier = Modifier.fillMaxWidth(0.85f),
                    )
                }

                // TTS voice
                if (voiceList.isNotEmpty()) {
                    item {
                        val idx = voiceList.indexOf(ttsVoice).coerceAtLeast(0)
                        val shortName = voiceList.getOrNull(idx)
                            ?.replace(".*#".toRegex(), "")
                            ?.replace(".*-x-".toRegex(), "")
                            ?.take(12)
                            ?: "default"
                        Text(
                            text = "Voice: $shortName  \u25B6",
                            color = WarmWhite,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable {
                                    val nextIdx = (idx + 1) % voiceList.size
                                    ttsVoice = voiceList[nextIdx]
                                    save()
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                },
                        )
                    }
                }

                item {
                    Text(
                        "v0.2.0",
                        color = DimText,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
