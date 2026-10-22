package com.watchreader.mobile.tts

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** The speeds the reader offers, from a little slower than speech to twice as fast. */
object SpeechRates {
    val RATES: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    const val NORMAL = 1f

    /** The offered speed nearest [rate], so a value saved by an older version still lands on one. */
    fun nearest(rate: Float): Float = RATES.minByOrNull { kotlin.math.abs(it - rate) } ?: NORMAL

    /** One step up or down the list, staying at its ends. */
    fun step(rate: Float, by: Int): Float {
        val index = RATES.indexOf(nearest(rate))
        return RATES[(index + by).coerceIn(0, RATES.size - 1)]
    }

    /** "1×", "1.25×", "0.75×". */
    fun label(rate: Float): String {
        val r = nearest(rate)
        return (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()) + "×"
    }
}

/**
 * The choices made for reading aloud on the phone, kept with the reader's other settings in
 * "reader_appearance": the speed, the voice picked for each language, and the language a book's
 * Latin-script text is read in when the reader has corrected the guess.
 */
class ReadAloudPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(NAME, Context.MODE_PRIVATE))

    var speechRate: Float
        get() = SpeechRates.nearest(prefs.getFloat(KEY_RATE, SpeechRates.NORMAL))
        set(value) = prefs.edit().putFloat(KEY_RATE, SpeechRates.nearest(value)).apply()

    /** The name of the voice chosen for [locale]'s language; null for the engine's own choice. */
    fun voice(locale: Locale): String? = prefs.getString(KEY_VOICE + languageKey(locale), null)

    fun setVoice(locale: Locale, name: String?) {
        val key = KEY_VOICE + languageKey(locale)
        prefs.edit().apply { if (name == null) remove(key) else putString(key, name) }.apply()
    }

    /** The language [bookId]'s Latin-script text is read in, when the reader chose it; null to guess. */
    fun latinLanguage(bookId: String): Locale? =
        prefs.getString(KEY_LATIN + bookId, null)?.let { Locale.forLanguageTag(it) }?.takeIf { it.language.isNotEmpty() }

    fun setLatinLanguage(bookId: String, locale: Locale?) {
        val key = KEY_LATIN + bookId
        prefs.edit().apply { if (locale == null) remove(key) else putString(key, locale.toLanguageTag()) }.apply()
    }

    /** Whether the phone has asked, once, for leave to show the listening notification. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, value).apply()

    companion object {
        const val NAME = "reader_appearance"
        private const val KEY_RATE = "tts_speech_rate"
        private const val KEY_VOICE = "tts_voice_"
        private const val KEY_LATIN = "tts_latin_"
        private const val KEY_NOTIFICATIONS_ASKED = "tts_notifications_asked"

        /** One key per language whatever the region, the way LanguageDetector.sameLanguage compares them. */
        private fun languageKey(locale: Locale): String = runCatching { locale.isO3Language }.getOrDefault(locale.language)
    }
}
