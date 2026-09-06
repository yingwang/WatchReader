package com.watchreader.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import com.watchreader.wear.settings.ReaderTheme

val BlueAccent = Color(0xFF9FC5FF)
val WarmBlack = Color(0xFF121210)
val WarmWhite = Color(0xFFE8E0D4)
val DimText = Color(0xFF8A8278)

val SepiaBg = Color(0xFFF1E4C8)
val SepiaText = Color(0xFF3B2E1E)
val SepiaDim = Color(0xFF8B7355)

/** The rows of the library and settings lists share one look. */
val ListRowBg = Color(0xFF1B222D)
val ListRowText = Color(0xFFE5EBF4)
val ListRowSub = Color(0xFFAAB6C7)
val ListTitle = BlueAccent

/** Colours of the reading page for a [ReaderTheme]. */
class PageColors(val background: Color, val text: Color, val dim: Color, val highlight: Color)

fun pageColors(theme: ReaderTheme): PageColors = when (theme) {
    ReaderTheme.DARK -> PageColors(WarmBlack, WarmWhite, DimText, BlueAccent.copy(alpha = 0.3f))
    ReaderTheme.SEPIA -> PageColors(SepiaBg, SepiaText, SepiaDim, Color(0xFFC9A86A).copy(alpha = 0.45f))
}

private val WatchReaderColors = Colors(
    primary = BlueAccent,
    primaryVariant = Color(0xFF608AC4),
    secondary = BlueAccent,
    background = WarmBlack,
    surface = ListRowBg,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFDDDDDD),
    onSurface = Color(0xFFDDDDDD),
    error = Color(0xFFEF5350),
    onError = Color.White,
)

@Composable
fun WatchReaderWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WatchReaderColors,
        content = content,
    )
}
