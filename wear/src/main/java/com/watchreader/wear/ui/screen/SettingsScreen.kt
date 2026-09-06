package com.watchreader.wear.ui.screen

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchreader.wear.BuildConfig
import com.watchreader.wear.R
import com.watchreader.wear.reader.Typefaces
import com.watchreader.wear.settings.ReaderPrefs
import com.watchreader.wear.settings.ReaderTheme
import com.watchreader.wear.tts.TtsLanguages
import com.watchreader.wear.ui.theme.BlueAccent
import com.watchreader.wear.ui.theme.ListRowBg
import com.watchreader.wear.ui.theme.ListRowSub
import com.watchreader.wear.ui.theme.ListRowText
import com.watchreader.wear.ui.theme.ListTitle
import com.watchreader.wear.ui.theme.pageColors
import java.util.Locale
import kotlin.math.roundToInt

/** Separate destinations preserve native swipe-to-return and keep each task short. */
@Composable
fun SettingsScreen(onAppearance: () -> Unit, onSpeech: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ReaderPrefs(context) }
    var keepScreenOn by remember { mutableStateOf(prefs.keepScreenOn) }
    SettingsList {
        item { SectionTitle(stringResource(R.string.settings_title)) }
        item { SettingsLink(stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_hint), onAppearance) }
        item { SettingsLink(stringResource(R.string.settings_section_speech), stringResource(R.string.settings_speech_hint), onSpeech) }
        item {
            ToggleChip(
                checked = keepScreenOn,
                onCheckedChange = { keepScreenOn = it; prefs.keepScreenOn = it },
                label = { Text(stringResource(R.string.settings_keep_screen_on), fontSize = 14.sp) },
                secondaryLabel = { Text(stringResource(R.string.settings_keep_screen_on_hint), fontSize = 12.sp) },
                toggleControl = { Switch(checked = keepScreenOn) },
                colors = ToggleChipDefaults.toggleChipColors(
                    checkedStartBackgroundColor = ListRowBg,
                    checkedEndBackgroundColor = ListRowBg,
                    uncheckedStartBackgroundColor = ListRowBg,
                    uncheckedEndBackgroundColor = ListRowBg,
                ),
                modifier = Modifier.fillMaxWidth(0.84f),
            )
        }
        item { Caption(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) }
    }
}

@Composable
fun AppearanceScreen(onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ReaderPrefs(context) }
    SettingsList {
        item { SectionTitle(stringResource(R.string.settings_appearance)) }
        item { SettingsLink(stringResource(R.string.settings_font_size), prefs.fontSize.toString(), { onEdit("size") }) }
        item { SettingsLink(stringResource(R.string.settings_typeface), Typefaces.labelFor(prefs.fontFamily), { onEdit("font") }) }
        item { SettingsLink(stringResource(R.string.settings_theme), stringResource(if (prefs.theme == ReaderTheme.DARK) R.string.settings_theme_dark else R.string.settings_theme_sepia), { onEdit("page") }) }
    }
}

/** One adjustment per page: the live sample and its control share the same viewport. */
@Composable
fun AppearanceEditor(kind: String) {
    val context = LocalContext.current
    val prefs = remember { ReaderPrefs(context) }
    val view = LocalView.current
    var fontSize by remember { mutableIntStateOf(prefs.fontSize) }
    var fontFamily by remember { mutableStateOf(prefs.fontFamily) }
    var theme by remember { mutableStateOf(prefs.theme) }
    val faces = remember { Typefaces.available() }
    val colors = pageColors(theme)
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = maxWidth * 0.13f, vertical = maxHeight * 0.13f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Text(
                stringResource(when (kind) { "size" -> R.string.settings_font_size; "font" -> R.string.settings_typeface; else -> R.string.settings_theme }),
                color = colors.text, fontSize = 13.sp,
            )
            Text(
                stringResource(R.string.settings_preview_short),
                color = colors.text,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.4f).sp,
                fontFamily = remember(fontFamily) { Typefaces.familyFor(fontFamily) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            )
            when (kind) {
                "size" -> {
                    Text(fontSize.toString(), color = colors.text, fontSize = 12.sp)
                    InlineSlider(
                        value = fontSize.toFloat(),
                        onValueChange = { fontSize = it.roundToInt(); prefs.fontSize = fontSize; tick() },
                        valueRange = ReaderPrefs.MIN_FONT.toFloat()..ReaderPrefs.MAX_FONT.toFloat(),
                        steps = ReaderPrefs.MAX_FONT - ReaderPrefs.MIN_FONT - 1,
                        decreaseIcon = { Icon(InlineSliderDefaults.Decrease, contentDescription = null) },
                        increaseIcon = { Icon(InlineSliderDefaults.Increase, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                "font" -> SettingsLink(Typefaces.labelFor(fontFamily), stringResource(R.string.settings_next_font), {
                    val next = (faces.indexOfFirst { it.key == fontFamily } + 1) % faces.size
                    fontFamily = faces[next].key
                    prefs.fontFamily = fontFamily
                    tick()
                }, Modifier.fillMaxWidth())
                else -> SettingsLink(
                    stringResource(if (theme == ReaderTheme.DARK) R.string.settings_theme_dark else R.string.settings_theme_sepia),
                    stringResource(R.string.settings_next_theme),
                    {
                        theme = if (theme == ReaderTheme.DARK) ReaderTheme.SEPIA else ReaderTheme.DARK
                        prefs.theme = theme
                        tick()
                    }, Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun SpeechSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { ReaderPrefs(context) }
    val view = LocalView.current
    var speechRate by remember { mutableFloatStateOf(prefs.speechRate) }
    var voices by remember { mutableStateOf<TtsLanguages.Availability?>(null) }
    DisposableEffect(Unit) {
        val engine = TtsLanguages.probe(context) { voices = it }
        onDispose { TtsLanguages.release(engine) }
    }
    SettingsList {
        item { SectionTitle(stringResource(R.string.settings_section_speech)) }
        item {
            Column(Modifier.fillMaxWidth(0.84f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.settings_speech_rate) + " " + String.format(Locale.US, "%.2f×", speechRate), color = BlueAccent, fontSize = 14.sp)
                InlineSlider(
                    value = speechRate,
                    onValueChange = { speechRate = it; prefs.speechRate = it; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                    valueRange = 0.5f..2f, steps = 5,
                    decreaseIcon = { Icon(InlineSliderDefaults.Decrease, contentDescription = null) },
                    increaseIcon = { Icon(InlineSliderDefaults.Increase, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        voices?.let { v ->
            item { Caption(if (v.installed.isEmpty()) stringResource(R.string.settings_voices_none) else stringResource(R.string.settings_voices_have, v.installed.joinToString(", ") { TtsLanguages.label(it) })) }
            if (v.missing.isNotEmpty()) item { Caption(stringResource(R.string.settings_voices_missing, v.missing.joinToString(", ") { TtsLanguages.label(it) })) }
        }
    }
}

@Composable
private fun SettingsList(content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(state = state, modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Chip(
        onClick = onClick,
        label = { Text(title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(subtitle, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        colors = ChipDefaults.chipColors(backgroundColor = ListRowBg, contentColor = ListRowText, secondaryContentColor = ListRowSub),
        modifier = modifier.fillMaxWidth(0.84f).heightIn(min = 48.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, color = ListTitle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 4.dp))
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, color = ListRowSub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f).padding(vertical = 6.dp))
}
