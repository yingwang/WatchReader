package com.watchreader.mobile.tts

import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Lets the reader hear a voice as it is chosen: one short sentence in its language
 * ([VoiceChoices.sample]), spoken through the voice sheet's own engine, which goes when the sheet
 * does. It is for a book that is not being read aloud at the moment; while it is, the choice is
 * heard straight away in the book itself.
 *
 * The sample asks for no audio focus. It lasts a second or two, and asking would take the focus
 * from reading paused by a passing interruption, which goes on by itself when the focus returns.
 */
internal object VoicePreview {
    private const val UTTERANCE = "voice-preview"

    /** Speaks the sample in the voice saved as [voiceName], or in [locale]'s default voice for null. */
    fun speak(engine: TextToSpeech, locale: Locale, voiceName: String?, rate: Float) {
        val voice = voiceName?.let { TtsLanguages.voiceNamed(engine, it) }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) engine.setLanguage(locale)
        engine.setSpeechRate(rate)
        engine.speak(VoiceChoices.sample(voice?.locale ?: locale), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }
}
