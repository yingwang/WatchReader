package com.watchreader.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

private val LibraryColorScheme = lightColorScheme(
    primary = Color(0xFF2C61AE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FF),
    onPrimaryContainer = Color(0xFF183B6D),
    secondary = Color(0xFF526782),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECF1F8),
    onSecondaryContainer = Color(0xFF283F5C),
    tertiary = Color(0xFF526782),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECF1F8),
    onTertiaryContainer = Color(0xFF283F5C),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF2F8),
    surfaceContainer = Color(0xFFF3F6FB),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFEDF2F8),
    surfaceContainerHighest = Color(0xFFE5ECF5),
    surfaceContainerLowest = Color.White,
    surfaceTint = Color(0xFF2C61AE),
    onBackground = Color(0xFF1C2D43),
    onSurface = Color(0xFF1C2D43),
    onSurfaceVariant = Color(0xFF526278),
    outline = Color(0xFF738198),
    outlineVariant = Color(0xFFDCE4EF),
)

@Composable
fun WatchReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LibraryColorScheme,
        content = content,
    )
}

/** Reading keeps its warm, low-glare page independently of the library chrome. */
@Composable
fun ReadingTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val statusColor = window?.statusBarColor
        val navigationColor = window?.navigationBarColor
        val lightStatus = controller?.isAppearanceLightStatusBars ?: true
        val lightNavigation = controller?.isAppearanceLightNavigationBars ?: true
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        window?.statusBarColor = Color(0xFF121210).toArgb()
        window?.navigationBarColor = Color(0xFF121210).toArgb()
        onDispose {
            controller?.isAppearanceLightStatusBars = lightStatus
            controller?.isAppearanceLightNavigationBars = lightNavigation
            if (statusColor != null) window.statusBarColor = statusColor
            if (navigationColor != null) window.navigationBarColor = navigationColor
        }
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9FC5FF),
            secondary = Color(0xFF9FC5FF),
            background = Color(0xFF121210),
            surface = Color(0xFF121210),
            onBackground = Color(0xFFE8E0D4),
            onSurface = Color(0xFFE8E0D4),
        ),
        content = content,
    )
}
