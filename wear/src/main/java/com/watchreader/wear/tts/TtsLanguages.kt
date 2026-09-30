package com.watchreader.wear.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Which of the languages this app reads aloud the watch can actually speak.
 *
 * The reader never asks the user to pick a voice: every sentence is spoken in the language it is
 * written in. What the user does need to know is whether the engine on this particular watch has
 * the voice for that language, because a missing voice is silent rather than loud.
 *
 * Asking the engine whether a language is available is not enough to know. Google's engine answers
 * yes for every language it can fetch, installed or not, and fetches the voice only the first time
 * something is read in it. The voices it lists say which is which: a voice still to be fetched is
 * marked [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED].
 */
object TtsLanguages {
    /**
     * Listed on the settings page whatever books are on the watch, as they always were. Any other
     * language is listed once a book on the watch is read aloud in it (see [shown]).
     */
    val SUPPORTED = listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)

    enum class State {
        /** A voice for the language is on the watch and reads without a connection. */
        INSTALLED,
        /** The engine has a voice for the language but fetches it the first time it is used. */
        DOWNLOAD,
        /** The engine has no voice for the language at all. */
        NONE,
    }

    /** One voice an engine lists, as far as it matters here. */
    data class VoiceInfo(val locale: Locale, val onWatch: Boolean)

    data class Availability(val installed: List<Locale>, val download: List<Locale>, val missing: List<Locale>) {
        /** The same answer for [locales] alone, each list keeping its order. */
        fun only(locales: Set<Locale>) = Availability(
            installed.filter { it in locales },
            download.filter { it in locales },
            missing.filter { it in locales },
        )
    }

    /** The languages the settings page reports on, given those the books on the watch need. */
    fun shown(needed: Set<Locale>): Set<Locale> = SUPPORTED.toSet() + needed

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
            mine.any { it.onWatch } -> State.INSTALLED
            mine.isNotEmpty() -> State.DOWNLOAD
            else -> State.NONE
        }
    }

    /** The voices [engine] lists; empty if it lists none or will not say. */
    fun voices(engine: TextToSpeech): List<VoiceInfo> =
        runCatching { engine.voices }.getOrNull().orEmpty().mapNotNull { voice ->
            val locale = voice.locale ?: return@mapNotNull null
            val notInstalled = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
            VoiceInfo(locale, onWatch = !voice.isNetworkConnectionRequired && !notInstalled)
        }

    /** What [engine] can do for [locale], asking it directly. */
    fun stateOf(engine: TextToSpeech, locale: Locale, voices: List<VoiceInfo> = voices(engine)): State =
        stateOf(locale, voices, runCatching { engine.isLanguageAvailable(locale) }.getOrNull())

    /**
     * Asks the engine once, about every language a sentence can be read in, and hands the answer
     * back; the engine is shut down either way. A watch with no speech engine at all answers with
     * every list empty, which the settings screen reports rather than passing over in silence.
     */
    fun probe(context: Context, onResult: (Availability) -> Unit): TextToSpeech {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                onResult(Availability(emptyList(), emptyList(), emptyList()))
                return@TextToSpeech
            }
            val voices = voices(tts)
            val states = LanguageDetector.LANGUAGES.associateWith { stateOf(tts, it, voices) }
            onResult(
                Availability(
                    installed = states.filterValues { it == State.INSTALLED }.keys.toList(),
                    download = states.filterValues { it == State.DOWNLOAD }.keys.toList(),
                    missing = states.filterValues { it == State.NONE }.keys.toList(),
                ),
            )
        }
        return engine
    }

    /**
     * Shuts an engine down off the main thread. shutdown() takes the engine's own lock, which the
     * connection set-up holds while it talks to the speech service; on a watch that can take
     * seconds, and waiting for it on the main thread is an ANR.
     */
    fun release(engine: TextToSpeech?) {
        if (engine == null) return
        thread(name = "tts-release") { runCatching { engine.shutdown() } }
    }

    /** The language's English name ("Chinese", "French"); the app's screens are in English. */
    fun label(locale: Locale): String = locale.getDisplayLanguage(Locale.ENGLISH)
}
