package com.watchreader.wear.ui.screen

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.RadioButton
import androidx.wear.compose.material.RadioButtonDefaults
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.SwitchDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.watchreader.wear.BuildConfig
import com.watchreader.wear.R
import com.watchreader.wear.reader.Typefaces
import com.watchreader.wear.settings.ReaderPrefs
import com.watchreader.wear.settings.ReaderTheme
import com.watchreader.wear.tts.TtsLanguages
import com.watchreader.wear.ui.theme.GreenAccent
import com.watchreader.wear.ui.theme.ListRowBg
import com.watchreader.wear.ui.theme.ListRowSub
import com.watchreader.wear.ui.theme.ListRowText
import com.watchreader.wear.ui.theme.ListTitle
import com.watchreader.wear.ui.theme.WarmBlack
import com.watchreader.wear.ui.theme.pageColors
import java.util.Locale
import kotlin.math.roundToInt

/** Every row is this wide, the same as the rows of the library. */
private const val ROW_WIDTH = 0.84f
private val RowShape = RoundedCornerShape(24.dp)

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
    var pickingTypeface by remember { mutableStateOf(false) }
    // Which of the two reading languages this watch can actually speak, or null until asked.
    var voices by remember { mutableStateOf<TtsLanguages.Availability?>(null) }
    val faces = remember { Typefaces.available() }

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
            item { SectionTitle(stringResource(R.string.settings_title)) }

            // The page as it will look, redrawn as the rows below change.
            item { PagePreview(theme = theme, fontSize = fontSize, fontFamily = fontFamily) }

            item {
                SliderRow(
                    name = stringResource(R.string.settings_font_size),
                    value = stringResource(R.string.settings_font_size_value, fontSize),
                    position = fontSize.toFloat(),
                    range = ReaderPrefs.MIN_FONT.toFloat()..ReaderPrefs.MAX_FONT.toFloat(),
                    steps = ReaderPrefs.MAX_FONT - ReaderPrefs.MIN_FONT - 1,
                    onChange = {
                        fontSize = it.roundToInt()
                        prefs.fontSize = fontSize
                        tick()
                    },
                )
            }

            item {
                val family = remember(fontFamily) { Typefaces.familyFor(fontFamily) }
                ValueRow(
                    name = stringResource(R.string.settings_typeface),
                    value = Typefaces.labelFor(fontFamily),
                    valueFamily = family,
                    onClick = { pickingTypeface = true },
                )
            }

            item {
                val colors = pageColors(theme)
                ValueRow(
                    name = stringResource(R.string.settings_theme),
                    value = stringResource(if (theme == ReaderTheme.DARK) R.string.settings_theme_dark else R.string.settings_theme_sepia),
                    icon = { Swatch(colors.background) },
                    onClick = {
                        theme = if (theme == ReaderTheme.DARK) ReaderTheme.SEPIA else ReaderTheme.DARK
                        prefs.theme = theme
                        tick()
                    },
                )
            }

            item {
                ToggleChip(
                    checked = keepScreenOn,
                    onCheckedChange = {
                        keepScreenOn = it
                        prefs.keepScreenOn = it
                        tick()
                    },
                    label = { Text(stringResource(R.string.settings_keep_screen_on), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(stringResource(R.string.settings_keep_screen_on_hint), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    toggleControl = { Switch(checked = keepScreenOn, colors = switchColors()) },
                    colors = rowToggleColors(),
                    shape = RowShape,
                    modifier = Modifier.fillMaxWidth(ROW_WIDTH),
                )
            }

            item { SectionTitle(stringResource(R.string.settings_section_speech), modifier = Modifier.padding(top = 12.dp)) }

            item {
                SliderRow(
                    name = stringResource(R.string.settings_speech_rate),
                    value = stringResource(R.string.settings_speech_rate_value, rateLabel(speechRate)),
                    position = speechRate,
                    range = 0.5f..2.0f,
                    steps = 5,
                    onChange = {
                        speechRate = it
                        prefs.speechRate = it
                        tick()
                    },
                )
            }

            voices?.let { v ->
                item {
                    // The voice is never chosen by hand: each sentence is spoken in its own
                    // language. All this line does is say whether the watch has that voice.
                    Caption(
                        text = if (v.installed.isEmpty()) {
                            stringResource(R.string.settings_voices_none)
                        } else {
                            stringResource(
                                R.string.settings_voices_have,
                                v.installed.joinToString(", ") { TtsLanguages.label(it) },
                            )
                        },
                        color = ListRowText,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (v.missing.isNotEmpty()) {
                    item {
                        Caption(
                            text = stringResource(
                                R.string.settings_voices_missing,
                                v.missing.joinToString(", ") { TtsLanguages.label(it) },
                            ),
                            color = ListRowSub,
                        )
                    }
                }
            }

            item {
                Caption(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    color = ListRowSub,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }

    val dialogScroll = rememberScalingLazyListState()
    Dialog(
        showDialog = pickingTypeface,
        onDismissRequest = { pickingTypeface = false },
        scrollState = dialogScroll,
    ) {
        Alert(
            title = { Text(stringResource(R.string.settings_typeface), color = ListTitle, fontSize = 14.sp) },
            scrollState = dialogScroll,
            backgroundColor = WarmBlack,
            // Room under the last face, so it can scroll up off the round edge like the others.
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 56.dp),
        ) {
            // Each face is shown in itself, so the choice is made by eye rather than by name.
            items(faces, key = { it.key }) { face ->
                val chosen = face.key == fontFamily
                ToggleChip(
                    checked = chosen,
                    onCheckedChange = {
                        fontFamily = face.key
                        prefs.fontFamily = face.key
                        tick()
                        pickingTypeface = false
                    },
                    label = { Text(face.label, fontFamily = face.family(), fontSize = 14.sp) },
                    secondaryLabel = {
                        Text(
                            stringResource(R.string.settings_typeface_sample),
                            fontFamily = face.family(),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    toggleControl = {
                        RadioButton(
                            selected = chosen,
                            colors = RadioButtonDefaults.colors(
                                selectedRingColor = GreenAccent,
                                selectedDotColor = GreenAccent,
                                unselectedRingColor = ListRowSub,
                                unselectedDotColor = ListRowSub,
                            ),
                        )
                    },
                    colors = rowToggleColors(),
                    shape = RowShape,
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = ListTitle,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Caption(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(0.9f),
    )
}

/** A few words set exactly as the reading page will set them. */
@Composable
private fun PagePreview(theme: ReaderTheme, fontSize: Int, fontFamily: String) {
    val colors = pageColors(theme)
    val family = remember(fontFamily) { Typefaces.familyFor(fontFamily) }
    Box(
        modifier = Modifier
            .fillMaxWidth(ROW_WIDTH)
            .clip(RowShape)
            .background(colors.background)
            .border(1.dp, colors.dim.copy(alpha = 0.35f), RowShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_preview),
            color = colors.text,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.4f).sp,
            fontFamily = family,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Clip,
        )
    }
}

/** A name over its value, with the slider underneath; the value is the one thing in colour. */
@Composable
private fun SliderRow(
    name: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(ROW_WIDTH).padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = ListRowText, fontSize = 13.sp)
            Text(value, color = GreenAccent, fontSize = 13.sp)
        }
        InlineSlider(
            value = position,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            increaseIcon = { Icon(InlineSliderDefaults.Increase, contentDescription = null, tint = ListRowText) },
            decreaseIcon = { Icon(InlineSliderDefaults.Decrease, contentDescription = null, tint = ListRowText) },
            colors = InlineSliderDefaults.colors(
                backgroundColor = ListRowBg,
                selectedBarColor = GreenAccent,
                unselectedBarColor = ListRowSub.copy(alpha = 0.4f),
                spacerColor = WarmBlack,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A row that reads name over value, like a book over its progress; tapping changes the value. */
@Composable
private fun ValueRow(
    name: String,
    value: String,
    onClick: () -> Unit,
    valueFamily: FontFamily? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    Chip(
        onClick = onClick,
        label = { Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(value, fontSize = 11.sp, fontFamily = valueFamily, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = icon?.let { { it() } },
        colors = ChipDefaults.chipColors(
            backgroundColor = ListRowBg,
            contentColor = ListRowText,
            secondaryContentColor = ListRowSub,
            iconColor = ListRowText,
        ),
        shape = RowShape,
        modifier = Modifier.fillMaxWidth(ROW_WIDTH),
    )
}

/** The colour of the page, as a small disc. */
@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(ChipDefaults.IconSize)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, ListRowSub, CircleShape),
    )
}

@Composable
private fun rowToggleColors() = ToggleChipDefaults.toggleChipColors(
    checkedStartBackgroundColor = ListRowBg,
    checkedEndBackgroundColor = ListRowBg,
    checkedContentColor = ListRowText,
    checkedSecondaryContentColor = ListRowSub,
    checkedToggleControlColor = GreenAccent,
    uncheckedStartBackgroundColor = ListRowBg,
    uncheckedEndBackgroundColor = ListRowBg,
    uncheckedContentColor = ListRowText,
    uncheckedSecondaryContentColor = ListRowSub,
    uncheckedToggleControlColor = ListRowSub,
)

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = GreenAccent,
    checkedTrackColor = GreenAccent.copy(alpha = 0.5f),
    uncheckedThumbColor = ListRowSub,
    uncheckedTrackColor = ListRowSub.copy(alpha = 0.5f),
)

/** 1, 1.5 and 1.25 rather than 1.0, 1.5 and 1.3. */
private fun rateLabel(rate: Float): String {
    val hundredths = (rate * 100).roundToInt()
    return when {
        hundredths % 100 == 0 -> (hundredths / 100).toString()
        hundredths % 10 == 0 -> String.format(Locale.US, "%.1f", rate)
        else -> String.format(Locale.US, "%.2f", rate)
    }
}
