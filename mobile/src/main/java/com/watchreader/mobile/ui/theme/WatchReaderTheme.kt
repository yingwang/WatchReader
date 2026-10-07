package com.watchreader.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

// Every scheme below names its containers, surfaces and inverse colours. One left out falls back
// to Material's purple baseline, and a sheet, a menu, a dialog or a snackbar action reaches for it.

private val LibraryColorScheme = lightColorScheme(
    primary = Color(0xFF536B85),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE4DC),
    onPrimaryContainer = Color(0xFF29282B),
    inversePrimary = Color(0xFFC6B69A),
    secondary = Color(0xFF716A63),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE4DC),
    onSecondaryContainer = Color(0xFF38332E),
    tertiary = Color(0xFF716A63),
    onTertiary = Color(0xFFF7F4EE),
    tertiaryContainer = Color(0xFFEAE4DC),
    onTertiaryContainer = Color(0xFF38332E),
    background = Color(0xFFF7F4EE),
    surface = Color(0xFFF7F4EE),
    surfaceVariant = Color(0xFFEAE4DC),
    surfaceContainer = Color(0xFFF0EBE4),
    surfaceContainerLow = Color(0xFFF4F0EA),
    surfaceContainerHigh = Color(0xFFEAE4DC),
    surfaceContainerHighest = Color(0xFFDDD5CB),
    surfaceContainerLowest = Color(0xFFF7F4EE),
    surfaceBright = Color(0xFFF7F4EE),
    surfaceDim = Color(0xFFD8D1C7),
    surfaceTint = Color(0xFF536B85),
    inverseSurface = Color(0xFF29282B),
    inverseOnSurface = Color(0xFFEAE5DC),
    onBackground = Color(0xFF29282B),
    onSurface = Color(0xFF29282B),
    onSurfaceVariant = Color(0xFF68625C),
    outline = Color(0xFF817970),
    outlineVariant = Color(0xFFD8D1C7),
)

/** Neutral graphite and ivory with a restrained champagne accent. */
private val LibraryDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC6B69A),
    onPrimary = Color(0xFF29251F),
    primaryContainer = Color(0xFF35312D),
    onPrimaryContainer = Color(0xFFEAE5DC),
    inversePrimary = Color(0xFF536B85),
    secondary = Color(0xFFCBC2B6),
    onSecondary = Color(0xFF302C27),
    secondaryContainer = Color(0xFF35312D),
    onSecondaryContainer = Color(0xFFEAE5DC),
    tertiary = Color(0xFFCBC2B6),
    onTertiary = Color(0xFF302C27),
    tertiaryContainer = Color(0xFF35312D),
    onTertiaryContainer = Color(0xFFEAE5DC),
    background = Color(0xFF111114),
    surface = Color(0xFF111114),
    surfaceVariant = Color(0xFF323237),
    surfaceContainerLowest = Color(0xFF0C0C0F),
    surfaceContainerLow = Color(0xFF19191D),
    surfaceContainer = Color(0xFF242429),
    surfaceContainerHigh = Color(0xFF2B2B30),
    surfaceContainerHighest = Color(0xFF323237),
    surfaceBright = Color(0xFF3B3B40),
    surfaceDim = Color(0xFF111114),
    surfaceTint = Color(0xFFC6B69A),
    inverseSurface = Color(0xFFEAE5DC),
    inverseOnSurface = Color(0xFF2B2B30),
    onBackground = Color(0xFFEAE5DC),
    onSurface = Color(0xFFEAE5DC),
    onSurfaceVariant = Color(0xFFBEB7AE),
    outline = Color(0xFF958D83),
    outlineVariant = Color(0xFF46423E),
)

/** The library follows the system's dark mode; the window behind it does too, from values-night. */
@Composable
fun WatchReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) LibraryDarkColorScheme else LibraryColorScheme,
        content = content,
    )
}

/**
 * The page colours the reader chooses between, whatever the library looks like. [key] is how the
 * choice is kept in the reader's preferences; [isLight] asks for dark icons in the system bars.
 */
enum class PageTheme(val key: String, val isLight: Boolean, val colors: ColorScheme) {
    /** The warm, low-glare page the reader has always had. */
    DARK(
        key = "dark",
        isLight = false,
        colors = darkColorScheme(
            primary = Color(0xFFC6B69A),
            onPrimary = Color(0xFF29251F),
            primaryContainer = Color(0xFF35312D),
            onPrimaryContainer = Color(0xFFEAE5DC),
            inversePrimary = Color(0xFF536B85),
            secondary = Color(0xFFC6B69A),
            onSecondary = Color(0xFF29251F),
            secondaryContainer = Color(0xFF35312D),
            onSecondaryContainer = Color(0xFFEAE5DC),
            tertiary = Color(0xFFC6B69A),
            onTertiary = Color(0xFF29251F),
            tertiaryContainer = Color(0xFF35312D),
            onTertiaryContainer = Color(0xFFEAE5DC),
            background = Color(0xFF111114),
            surface = Color(0xFF111114),
            surfaceVariant = Color(0xFF323237),
            surfaceContainerLowest = Color(0xFF0C0C0F),
            surfaceContainerLow = Color(0xFF19191D),
            surfaceContainer = Color(0xFF242429),
            surfaceContainerHigh = Color(0xFF2B2B30),
            surfaceContainerHighest = Color(0xFF323237),
            surfaceBright = Color(0xFF3B3B40),
            surfaceDim = Color(0xFF111114),
            surfaceTint = Color(0xFFC6B69A),
            inverseSurface = Color(0xFFEAE5DC),
            inverseOnSurface = Color(0xFF242429),
            onBackground = Color(0xFFEAE5DC),
            onSurface = Color(0xFFEAE5DC),
            onSurfaceVariant = Color(0xFFB8B0A4),
            outline = Color(0xFF8A8378),
            outlineVariant = Color(0xFF3A3934),
        ),
    ),

    /** Paper white with near-black ink, and the library's blue for the few controls. */
    LIGHT(
        key = "light",
        isLight = true,
        colors = lightColorScheme(
            primary = Color(0xFF536B85),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEAE4DC),
            onPrimaryContainer = Color(0xFF1E1E1C),
            inversePrimary = Color(0xFFC6B69A),
            secondary = Color(0xFF536B85),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEAE4DC),
            onSecondaryContainer = Color(0xFF1E1E1C),
            tertiary = Color(0xFF536B85),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFEAE4DC),
            onTertiaryContainer = Color(0xFF1E1E1C),
            background = Color(0xFFF7F4EE),
            surface = Color(0xFFF7F4EE),
            surfaceVariant = Color(0xFFE4E0D7),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF5F2EB),
            surfaceContainer = Color(0xFFF0EDE5),
            surfaceContainerHigh = Color(0xFFEBE7DF),
            surfaceContainerHighest = Color(0xFFE4E0D7),
            surfaceBright = Color(0xFFF7F4EE),
            surfaceDim = Color(0xFFDCD8CF),
            surfaceTint = Color(0xFF536B85),
            inverseSurface = Color(0xFF32302C),
            inverseOnSurface = Color(0xFFF2EEE6),
            onBackground = Color(0xFF1E1E1C),
            onSurface = Color(0xFF1E1E1C),
            onSurfaceVariant = Color(0xFF5A564E),
            outline = Color(0xFF7D786E),
            outlineVariant = Color(0xFFD6D1C6),
        ),
    ),

    /** Old paper and brown ink; the controls take a brown too, as blue sits oddly on it. */
    SEPIA(
        key = "sepia",
        isLight = true,
        colors = lightColorScheme(
            primary = Color(0xFF7A4E24),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE6D3B0),
            onPrimaryContainer = Color(0xFF3B2F22),
            inversePrimary = Color(0xFFE3B98A),
            secondary = Color(0xFF7A4E24),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE6D3B0),
            onSecondaryContainer = Color(0xFF3B2F22),
            tertiary = Color(0xFF7A4E24),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE6D3B0),
            onTertiaryContainer = Color(0xFF3B2F22),
            background = Color(0xFFF4ECD8),
            surface = Color(0xFFF4ECD8),
            surfaceVariant = Color(0xFFDFD3B8),
            surfaceContainerLowest = Color(0xFFFBF5E6),
            surfaceContainerLow = Color(0xFFF0E7D2),
            surfaceContainer = Color(0xFFEBE1CA),
            surfaceContainerHigh = Color(0xFFE6DBC2),
            surfaceContainerHighest = Color(0xFFDFD3B8),
            surfaceBright = Color(0xFFF4ECD8),
            surfaceDim = Color(0xFFDCD0B5),
            surfaceTint = Color(0xFF7A4E24),
            inverseSurface = Color(0xFF3B2F22),
            inverseOnSurface = Color(0xFFF4ECD8),
            onBackground = Color(0xFF3B2F22),
            onSurface = Color(0xFF3B2F22),
            onSurfaceVariant = Color(0xFF62533F),
            outline = Color(0xFF86765F),
            outlineVariant = Color(0xFFD5C8AC),
        ),
    ),
    ;

    companion object {
        /** An unknown or missing key is the dark page, which is what every reader had before. */
        fun fromKey(key: String?): PageTheme = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** Reading keeps the page the reader chose independently of the library chrome. */
@Composable
fun ReadingTheme(pageTheme: PageTheme, content: @Composable () -> Unit) {
    val view = LocalView.current
    // The bars are put back as the library had them when the reader is left, whichever pages
    // were chosen in between.
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val statusColor = window?.statusBarColor
        val navigationColor = window?.navigationBarColor
        val lightStatus = controller?.isAppearanceLightStatusBars ?: true
        val lightNavigation = controller?.isAppearanceLightNavigationBars ?: true
        onDispose {
            controller?.isAppearanceLightStatusBars = lightStatus
            controller?.isAppearanceLightNavigationBars = lightNavigation
            if (statusColor != null) window.statusBarColor = statusColor
            if (navigationColor != null) window.navigationBarColor = navigationColor
        }
    }
    // The bars take the page's colour, with dark icons on a light page, and change with it while
    // the Appearance dialog is open.
    DisposableEffect(view, pageTheme) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = pageTheme.isLight
        controller?.isAppearanceLightNavigationBars = pageTheme.isLight
        window?.statusBarColor = pageTheme.colors.background.toArgb()
        window?.navigationBarColor = pageTheme.colors.background.toArgb()
        onDispose { }
    }
    MaterialTheme(
        colorScheme = pageTheme.colors,
        content = content,
    )
}
