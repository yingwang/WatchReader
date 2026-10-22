package com.watchreader.mobile.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Which of the languages this app reads aloud the phone can actually speak, and in which voices.
 *
 * Every sentence is spoken in the language it is written in, as on the watch. What the reader
 * needs to know is whether the engine on this phone has the voice for that language, because a
 * missing voice is silent rather than loud.
 *
 * Asking the engine whether a language is available is not enough to know. Google's engine answers
 * yes for every language it can fetch, installed or not, and fetches the voice only the first time
 * something is read in it. The voices it lists say which is which: a voice still to be fetched is
 * marked [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED].
 */
object TtsLanguages {
    enum class State {
        /** A voice for the language is on the phone and reads without a connection. */
        INSTALLED,
        /** The engine has a voice for the language but fetches it the first time it is used. */
        DOWNLOAD,
        /** The engine has no voice for the language at all. */
        NONE,
    }

    /** One voice an engine lists, as far as it matters here. */
    data class VoiceInfo(val locale: Locale, val onPhone: Boolean)

    /**
     * What an engine can do for [locale], given the voices it lists and its answer to
     * isLanguageAvailable. An engine that lists no voices at all is taken at its word.
     */
    fun stateOf(locale: Locale, voices: Collection<VoiceInfo>, available: Int?): State {
        if (voices.isEmpty()) {
            return if (available != null && available >= TextToSpeech.LANG_AVAILABLE) State.INSTALLED else State.NONE
        }
        val mine = voices.filter { LanguageDetector.sameLanguage(it.locale, locale) }
        return when {
            mine.any { it.onPhone } -> State.INSTALLED
            mine.isNotEmpty() -> State.DOWNLOAD
            else -> State.NONE
        }
    }

    /** The voices [engine] lists; empty if it lists none or will not say. */
    fun voices(engine: TextToSpeech): List<VoiceInfo> =
        listed(engine).map { VoiceInfo(it.locale, onPhone = isOnPhone(it)) }

    /** What [engine] can do for [locale], asking it directly. */
    fun stateOf(engine: TextToSpeech, locale: Locale, voices: List<VoiceInfo> = voices(engine)): State =
        stateOf(locale, voices, runCatching { engine.isLanguageAvailable(locale) }.getOrNull())

    /**
     * The voices on the phone for [locale]'s language, the ones a reader can pick between, in a
     * steady order: by region, then by name. Voices that need a connection are left out; they
     * stop mid-book when the connection does.
     */
    fun choices(engine: TextToSpeech, locale: Locale): List<Voice> =
        listed(engine)
            .filter { isOnPhone(it) && LanguageDetector.sameLanguage(it.locale, locale) }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))

    /** The voice [engine] lists under [name], or null when it has gone (an engine update, an uninstall). */
    fun voiceNamed(engine: TextToSpeech, name: String): Voice? = listed(engine).firstOrNull { it.name == name }

    private fun listed(engine: TextToSpeech): List<Voice> =
        runCatching { engine.voices }.getOrNull().orEmpty().filter { it.locale != null }

    private fun isOnPhone(voice: Voice): Boolean =
        !voice.isNetworkConnectionRequired && voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

    /**
     * Shuts an engine down off the main thread. shutdown() takes the engine's own lock, which the
     * connection set-up holds while it talks to the speech service; waiting for it on the main
     * thread can be an ANR.
     */
    fun release(engine: TextToSpeech?) {
        if (engine == null) return
        thread(name = "tts-release") { runCatching { engine.shutdown() } }
    }

    /** The language's English name ("Chinese", "French"); the app's screens are in English. */
    fun label(locale: Locale): String = locale.getDisplayLanguage(Locale.ENGLISH)

    /**
     * A voice as the reader sees it: its region, and a number when the region has several, since
     * engines name voices with codes such as "en-us-x-iom-local" that say nothing to a reader.
     */
    fun voiceLabels(voices: List<Locale>): List<String> {
        val perRegion = voices.groupingBy { it.toLanguageTag() }.eachCount()
        val seen = HashMap<String, Int>()
        return voices.map { locale ->
            val tag = locale.toLanguageTag()
            val region = locale.getDisplayName(Locale.ENGLISH)
            if ((perRegion[tag] ?: 0) <= 1) region
            else "$region ${seen.merge(tag, 1, Int::plus)}"
        }
    }
}
