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
    primary = Color(0xFF2C61AE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FF),
    onPrimaryContainer = Color(0xFF183B6D),
    inversePrimary = Color(0xFFA9C7FF),
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
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCE4EF),
    surfaceTint = Color(0xFF2C61AE),
    inverseSurface = Color(0xFF2B3442),
    inverseOnSurface = Color(0xFFEEF2F8),
    onBackground = Color(0xFF1C2D43),
    onSurface = Color(0xFF1C2D43),
    onSurfaceVariant = Color(0xFF526278),
    outline = Color(0xFF738198),
    outlineVariant = Color(0xFFDCE4EF),
)

/** The same blue on a blue-grey night: the accent is lightened so it keeps its contrast. */
private val LibraryDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0B305F),
    primaryContainer = Color(0xFF1F3B63),
    onPrimaryContainer = Color(0xFFD7E3FF),
    inversePrimary = Color(0xFF2C61AE),
    secondary = Color(0xFFB6C8E2),
    onSecondary = Color(0xFF203248),
    secondaryContainer = Color(0xFF2A3A50),
    onSecondaryContainer = Color(0xFFD6E3F7),
    tertiary = Color(0xFFB6C8E2),
    onTertiary = Color(0xFF203248),
    tertiaryContainer = Color(0xFF2A3A50),
    onTertiaryContainer = Color(0xFFD6E3F7),
    background = Color(0xFF111418),
    surface = Color(0xFF111418),
    surfaceVariant = Color(0xFF2A313B),
    surfaceContainerLowest = Color(0xFF0C0F13),
    surfaceContainerLow = Color(0xFF181C22),
    surfaceContainer = Color(0xFF1C2027),
    surfaceContainerHigh = Color(0xFF232830),
    surfaceContainerHighest = Color(0xFF2A313B),
    surfaceBright = Color(0xFF32383F),
    surfaceDim = Color(0xFF111418),
    surfaceTint = Color(0xFFA9C7FF),
    inverseSurface = Color(0xFFE1E6EE),
    inverseOnSurface = Color(0xFF232830),
    onBackground = Color(0xFFE1E6EE),
    onSurface = Color(0xFFE1E6EE),
    onSurfaceVariant = Color(0xFFB4BECB),
    outline = Color(0xFF8C96A4),
    outlineVariant = Color(0xFF3A424D),
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
            primary = Color(0xFF9FC5FF),
            onPrimary = Color(0xFF0F2A4D),
            primaryContainer = Color(0xFF263447),
            onPrimaryContainer = Color(0xFFE8E0D4),
            inversePrimary = Color(0xFF2C61AE),
            secondary = Color(0xFF9FC5FF),
            onSecondary = Color(0xFF0F2A4D),
            secondaryContainer = Color(0xFF263447),
            onSecondaryContainer = Color(0xFFE8E0D4),
            tertiary = Color(0xFF9FC5FF),
            onTertiary = Color(0xFF0F2A4D),
            tertiaryContainer = Color(0xFF263447),
            onTertiaryContainer = Color(0xFFE8E0D4),
            background = Color(0xFF121210),
            surface = Color(0xFF121210),
            surfaceVariant = Color(0xFF2E2D29),
            surfaceContainerLowest = Color(0xFF0D0D0B),
            surfaceContainerLow = Color(0xFF1A1A17),
            surfaceContainer = Color(0xFF1E1E1B),
            surfaceContainerHigh = Color(0xFF252521),
            surfaceContainerHighest = Color(0xFF2E2D29),
            surfaceBright = Color(0xFF383733),
            surfaceDim = Color(0xFF121210),
            surfaceTint = Color(0xFF9FC5FF),
            inverseSurface = Color(0xFFE8E0D4),
            inverseOnSurface = Color(0xFF1E1E1B),
            onBackground = Color(0xFFE8E0D4),
            onSurface = Color(0xFFE8E0D4),
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
            primary = Color(0xFF2C61AE),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE2EAF6),
            onPrimaryContainer = Color(0xFF1E1E1C),
            inversePrimary = Color(0xFF9FC5FF),
            secondary = Color(0xFF2C61AE),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE2EAF6),
            onSecondaryContainer = Color(0xFF1E1E1C),
            tertiary = Color(0xFF2C61AE),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE2EAF6),
            onTertiaryContainer = Color(0xFF1E1E1C),
            background = Color(0xFFFAF8F3),
            surface = Color(0xFFFAF8F3),
            surfaceVariant = Color(0xFFE4E0D7),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF5F2EB),
            surfaceContainer = Color(0xFFF0EDE5),
            surfaceContainerHigh = Color(0xFFEBE7DF),
            surfaceContainerHighest = Color(0xFFE4E0D7),
            surfaceBright = Color(0xFFFAF8F3),
            surfaceDim = Color(0xFFDCD8CF),
            surfaceTint = Color(0xFF2C61AE),
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
