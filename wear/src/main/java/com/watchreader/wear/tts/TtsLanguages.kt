package com.watchreader.wear.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Which of the languages this app reads aloud the watch can actually speak.
 *
 * The reader never asks the user to pick a voice: every sentence is spoken in the language it is
 * written in. What the user does need to know is whether the engine on this particular watch has
 * the voice for that language at all, because a missing voice is silent rather than loud.
 */
object TtsLanguages {
    val SUPPORTED = listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)

    data class Availability(val installed: List<Locale>, val missing: List<Locale>)

    /**
     * Asks the engine once and hands the answer back; the engine is shut down either way. A watch
     * with no speech engine at all answers with both lists empty, which the settings screen reports
     * rather than passing over in silence.
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
            for (locale in SUPPORTED) {
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

    fun label(locale: Locale): String = if (locale.language == "zh") "中文" else "English"
}
