package com.watchreader.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

val WarmAmber = Color(0xFFE0C097)
val GreenAccent = Color(0xFF81C784)
val WarmBlack = Color(0xFF121210)
val WarmWhite = Color(0xFFE8E0D4)
val DimText = Color(0xFF8A8278)

// Sepia theme colors
val SepiaBg = Color(0xFFF5E6C8)
val SepiaText = Color(0xFF433422)
val SepiaAccent = Color(0xFF8B7355)

private val WatchReaderColors = Colors(
    primary = GreenAccent,
    primaryVariant = Color(0xFF5A8A5C),
    secondary = GreenAccent,
    background = WarmBlack,
    surface = Color(0xFF1A1A1A),
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
