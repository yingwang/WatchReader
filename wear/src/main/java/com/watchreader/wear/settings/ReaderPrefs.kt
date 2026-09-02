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

    /** Engine voice name, or empty for "follow the language of the text". */
    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

    companion object {
        const val MIN_FONT = 12
        const val MAX_FONT = 22
        private const val NAME = "watchreader_settings"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_THEME = "theme"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_TTS_VOICE = "tts_voice"
    }
}
