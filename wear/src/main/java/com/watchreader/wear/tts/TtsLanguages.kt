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
 * the voice for that language at all, because a missing voice is silent rather than loud.
 */
object TtsLanguages {
    /**
     * Listed on the settings page whatever books are on the watch, as they always were. Any other
     * language is listed once a book on the watch is read aloud in it (see [shown]).
     */
    val SUPPORTED = listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)

    data class Availability(val installed: List<Locale>, val missing: List<Locale>) {
        /** The same answer for [locales] alone, each list keeping its order. */
        fun only(locales: Set<Locale>) = Availability(installed.filter { it in locales }, missing.filter { it in locales })
    }

    /** The languages the settings page reports on, given those the books on the watch need. */
    fun shown(needed: Set<Locale>): Set<Locale> = SUPPORTED.toSet() + needed

    /**
     * Asks the engine once, about every language a sentence can be read in, and hands the answer
     * back; the engine is shut down either way. A watch with no speech engine at all answers with
     * both lists empty, which the settings screen reports rather than passing over in silence.
     */
    fun probe(context: Context, onResult: (Availability) -> Unit): TextToSpeech {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                onResult(Availability(emptyList(), emptyList()))
                return@TextToSpeech
            }
            val installed = mutableListOf<Locale>()
            val missing = mutableListOf<Locale>()
            for (locale in LanguageDetector.LANGUAGES) {
                val answer = runCatching { engine?.isLanguageAvailable(locale) }.getOrNull()
                when (answer) {
                    TextToSpeech.LANG_AVAILABLE,
                    TextToSpeech.LANG_COUNTRY_AVAILABLE,
                    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> installed += locale
                    else -> missing += locale
                }
            }
            onResult(Availability(installed, missing))
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
