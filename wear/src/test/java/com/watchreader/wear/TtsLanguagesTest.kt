package com.watchreader.wear

import com.watchreader.wear.tts.LanguageDetector
import com.watchreader.wear.tts.TtsLanguages
import org.junit.Assert.assertEquals
import org.junit.Test

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
            missing = listOf(LanguageDetector.CHINESE, LanguageDetector.THAI, LanguageDetector.FRENCH),
        )
        val shown = probed.only(TtsLanguages.shown(setOf(LanguageDetector.FRENCH, LanguageDetector.JAPANESE)))
        assertEquals(listOf(LanguageDetector.ENGLISH, LanguageDetector.JAPANESE), shown.installed)
        assertEquals(listOf(LanguageDetector.CHINESE, LanguageDetector.FRENCH), shown.missing)
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
