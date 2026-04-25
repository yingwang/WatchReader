package com.watchreader.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE0C097),
    secondary = Color(0xFF81C784),
    background = Color(0xFF121210),
    surface = Color(0xFF1E1C18),
    onPrimary = Color(0xFF1A1A1A),
    onSecondary = Color.Black,
    onBackground = Color(0xFFE8E0D4),
    onSurface = Color(0xFFE8E0D4),
)

@Composable
fun WatchReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
