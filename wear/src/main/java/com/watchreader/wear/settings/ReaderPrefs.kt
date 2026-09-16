package com.watchreader.wear.settings

import android.content.Context
import android.content.SharedPreferences

enum class ReaderTheme { DARK, SEPIA }

/** All user settings, in one SharedPreferences file, with the defaults in one place. */
class ReaderPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 16)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(MIN_FONT, MAX_FONT)).apply()

    /** "sans", "serif" or "kai". */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "sans") ?: "sans"
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    var theme: ReaderTheme
        get() = runCatching { ReaderTheme.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ReaderTheme.DARK)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    /** Auto page turn while reading, off unless the reader switches it on in Settings. */
    var autoTurnEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TURN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TURN_ENABLED, value).apply()

    /** Seconds a page stays open before auto page turn moves on. */
    var autoTurnSeconds: Float
        get() = prefs.getFloat(KEY_AUTO_TURN_SECONDS, DEFAULT_AUTO_TURN_SECONDS)
        set(value) = prefs.edit().putFloat(KEY_AUTO_TURN_SECONDS, value.coerceIn(MIN_AUTO_TURN_SECONDS, MAX_AUTO_TURN_SECONDS)).apply()

    var readerHintSeen: Boolean
        get() = prefs.getBoolean("reader_hint_seen", false)
        set(value) = prefs.edit().putBoolean("reader_hint_seen", value).apply()

    companion object {
        const val MIN_FONT = 12
        const val MAX_FONT = 22
        const val MIN_AUTO_TURN_SECONDS = 1f
        const val MAX_AUTO_TURN_SECONDS = 31f
        const val DEFAULT_AUTO_TURN_SECONDS = 8f
        /** The stops the slider and crown move through: finer where a second matters, coarser above. */
        val AUTO_TURN_LADDER = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 15f, 20f, 25f, 31f)
        fun autoTurnIndex(seconds: Float): Int {
            var best = 0
            for (i in AUTO_TURN_LADDER.indices) if (kotlin.math.abs(AUTO_TURN_LADDER[i] - seconds) < kotlin.math.abs(AUTO_TURN_LADDER[best] - seconds)) best = i
            return best
        }
        fun autoTurnStep(seconds: Float, delta: Int): Float =
            AUTO_TURN_LADDER[(autoTurnIndex(seconds) + delta).coerceIn(0, AUTO_TURN_LADDER.size - 1)]
        private const val KEY_AUTO_TURN_SECONDS = "auto_turn_seconds"
        private const val KEY_AUTO_TURN_ENABLED = "auto_turn_enabled"
        private const val NAME = "watchreader_settings"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_THEME = "theme"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SPEECH_RATE = "speech_rate"
    }
}
