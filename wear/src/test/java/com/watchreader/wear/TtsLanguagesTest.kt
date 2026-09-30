package com.watchreader.wear

import com.watchreader.wear.tts.LanguageDetector
import android.speech.tts.TextToSpeech
import com.watchreader.wear.tts.TtsLanguages
import com.watchreader.wear.tts.TtsLanguages.State
import com.watchreader.wear.tts.TtsLanguages.VoiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TtsLanguagesTest {
    @Test
    fun chineseAndEnglishAreAlwaysReportedAndOthersOnlyWhenABookNeedsThem() {
        assertEquals(setOf(LanguageDetector.ENGLISH, LanguageDetector.CHINESE), TtsLanguages.shown(emptySet()))
        assertEquals(
            setOf(LanguageDetector.ENGLISH, LanguageDetector.CHINESE, LanguageDetector.FRENCH),
            TtsLanguages.shown(setOf(LanguageDetector.FRENCH)),
        )
    }

    @Test
    fun theAnswerIsNarrowedToTheLanguagesShownInItsOwnOrder() {
        val probed = TtsLanguages.Availability(
            installed = listOf(LanguageDetector.ENGLISH, LanguageDetector.JAPANESE, LanguageDetector.GERMAN),
            download = listOf(LanguageDetector.SWEDISH, LanguageDetector.FRENCH),
            missing = listOf(LanguageDetector.CHINESE, LanguageDetector.THAI),
        )
        val shown = probed.only(TtsLanguages.shown(setOf(LanguageDetector.FRENCH, LanguageDetector.JAPANESE)))
        assertEquals(listOf(LanguageDetector.ENGLISH, LanguageDetector.JAPANESE), shown.installed)
        assertEquals(listOf(LanguageDetector.FRENCH), shown.download)
        assertEquals(listOf(LanguageDetector.CHINESE), shown.missing)
    }

    @Test
    fun theVoicesAnEngineListsSayWhatIsOnTheWatch() {
        // As Google's engine lists them on a Pixel Watch: English on the watch, Czech and Russian
        // still to fetch (their network voices marked not installed too), no Hebrew at all.
        val voices = listOf(
            VoiceInfo(Locale.forLanguageTag("en-US"), onWatch = true),
            VoiceInfo(Locale.forLanguageTag("en-US"), onWatch = false),
            VoiceInfo(Locale.forLanguageTag("zh-CN"), onWatch = true),
            VoiceInfo(Locale.forLanguageTag("zh-TW"), onWatch = true),
            VoiceInfo(Locale.forLanguageTag("cs-CZ"), onWatch = false),
            VoiceInfo(Locale.forLanguageTag("ru-RU"), onWatch = false),
            VoiceInfo(Locale("in", "ID"), onWatch = false),
        )
        // Google answers isLanguageAvailable with yes for a voice it has yet to fetch; the voices overrule it.
        val yes = TextToSpeech.LANG_COUNTRY_AVAILABLE
        assertEquals(State.INSTALLED, TtsLanguages.stateOf(LanguageDetector.ENGLISH, voices, yes))
        assertEquals(State.INSTALLED, TtsLanguages.stateOf(LanguageDetector.CHINESE, voices, yes))
        assertEquals(State.DOWNLOAD, TtsLanguages.stateOf(LanguageDetector.RUSSIAN, voices, yes))
        assertEquals(State.DOWNLOAD, TtsLanguages.stateOf(LanguageDetector.INDONESIAN, voices, yes))
        assertEquals(State.NONE, TtsLanguages.stateOf(LanguageDetector.HEBREW, voices, TextToSpeech.LANG_NOT_SUPPORTED))
        assertEquals(State.NONE, TtsLanguages.stateOf(LanguageDetector.FRENCH, voices, yes))
    }

    @Test
    fun anEngineThatListsNoVoicesIsTakenAtItsWord() {
        assertEquals(State.INSTALLED, TtsLanguages.stateOf(LanguageDetector.FRENCH, emptyList(), TextToSpeech.LANG_AVAILABLE))
        assertEquals(State.NONE, TtsLanguages.stateOf(LanguageDetector.FRENCH, emptyList(), TextToSpeech.LANG_MISSING_DATA))
        assertEquals(State.NONE, TtsLanguages.stateOf(LanguageDetector.FRENCH, emptyList(), null))
    }

    @Test
    fun languagesAreNamedInEnglish() {
        assertEquals("English", TtsLanguages.label(LanguageDetector.ENGLISH))
        assertEquals("Chinese", TtsLanguages.label(LanguageDetector.CHINESE))
        assertEquals("Japanese", TtsLanguages.label(LanguageDetector.JAPANESE))
        assertEquals("French", TtsLanguages.label(LanguageDetector.FRENCH))
        assertEquals("Russian", TtsLanguages.label(LanguageDetector.RUSSIAN))
    }
}
