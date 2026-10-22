package com.watchreader.mobile

import com.watchreader.mobile.tts.LanguageDetector
import com.watchreader.mobile.tts.ReadAloudPlayback
import com.watchreader.mobile.tts.ReadAloudPrefs
import com.watchreader.mobile.tts.ReadAloudState
import com.watchreader.mobile.tts.SleepTimer
import com.watchreader.mobile.tts.SpeechRates
import com.watchreader.mobile.tts.TtsLanguages
import com.watchreader.mobile.ui.screen.AutoTurnPrefs
import com.watchreader.mobile.ui.screen.isLatinBook
import com.watchreader.mobile.ui.screen.languagesShown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReadAloudSettingsTest {
    @After fun clear() = ReadAloudPlayback.set(ReadAloudState.IDLE, null, null)

    @Test fun speedsRunFromThreeQuartersToTwiceAndLandOnTheNearestStop() {
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), SpeechRates.RATES)
        assertEquals(1f, SpeechRates.nearest(1.1f))
        assertEquals(1.25f, SpeechRates.nearest(1.2f))
        assertEquals(0.75f, SpeechRates.nearest(0.1f))
        assertEquals(2f, SpeechRates.nearest(3.5f))
        assertEquals(1.25f, SpeechRates.step(1f, 1))
        assertEquals(0.75f, SpeechRates.step(1f, -1))
        assertEquals(2f, SpeechRates.step(2f, 1))
        assertEquals(0.75f, SpeechRates.step(0.75f, -1))
        assertEquals("1×", SpeechRates.label(1f))
        assertEquals("1.25×", SpeechRates.label(1.25f))
        assertEquals("0.75×", SpeechRates.label(0.75f))
        assertEquals("2×", SpeechRates.label(2f))
    }

    @Test fun readAloudChoicesSurviveARestart() {
        val stored = HashMap<String, Any?>()
        val prefs = ReadAloudPrefs(FakePreferences(stored))
        assertEquals(1f, prefs.speechRate)
        assertNull(prefs.voice(Locale.US))
        assertNull(prefs.latinLanguage("book"))
        assertFalse(prefs.notificationsAsked)

        prefs.speechRate = 1.5f
        prefs.setVoice(Locale.UK, "en-gb-x-gba-local")
        prefs.setVoice(LanguageDetector.CHINESE, "cmn-cn-x-ccc-local")
        prefs.setLatinLanguage("book", LanguageDetector.SWEDISH)
        prefs.notificationsAsked = true

        val again = ReadAloudPrefs(FakePreferences(stored))
        assertEquals(1.5f, again.speechRate)
        // One choice per language, whatever region a sentence's locale names.
        assertEquals("en-gb-x-gba-local", again.voice(Locale.US))
        assertEquals("cmn-cn-x-ccc-local", again.voice(Locale.TRADITIONAL_CHINESE))
        assertNull(again.voice(LanguageDetector.FRENCH))
        assertEquals("sv", again.latinLanguage("book")!!.language)
        assertNull(again.latinLanguage("other book"))
        assertTrue(again.notificationsAsked)

        again.setVoice(Locale.US, null)
        again.setLatinLanguage("book", null)
        assertNull(ReadAloudPrefs(FakePreferences(stored)).voice(Locale.UK))
        assertNull(ReadAloudPrefs(FakePreferences(stored)).latinLanguage("book"))
    }

    @Test fun aSpeedSavedOffTheListComesBackOnIt() {
        val stored = HashMap<String, Any?>()
        stored["tts_speech_rate"] = 1.6f
        assertEquals(1.5f, ReadAloudPrefs(FakePreferences(stored)).speechRate)
    }

    @Test fun autoTurnSettingsSurviveARestartBesideTheTextSettings() {
        // The text settings already in "reader_appearance" are left as they were.
        val stored = HashMap<String, Any?>(mapOf("font_size" to 20, "line_spacing" to "loose", "page_margin" to "wide", "font" to "kai", "page_theme" to "sepia"))
        val before = HashMap(stored)
        val prefs = AutoTurnPrefs(FakePreferences(stored))
        assertFalse(prefs.enabled)
        assertEquals(8, prefs.seconds)

        prefs.enabled = true
        prefs.seconds = 15
        val again = AutoTurnPrefs(FakePreferences(stored))
        assertTrue(again.enabled)
        assertEquals(15, again.seconds)
        for ((key, value) in before) assertEquals(key, value, stored[key])

        // A value off the ladder is put on its nearest stop, going in and coming out.
        again.seconds = 14
        assertEquals(15, AutoTurnPrefs(FakePreferences(stored)).seconds)
        stored["auto_turn_seconds"] = 1000
        assertEquals(120, AutoTurnPrefs(FakePreferences(stored)).seconds)
    }

    @Test fun autoTurnSecondsMoveAlongTheLadderAndStopAtItsEnds() {
        assertEquals(8, AutoTurnPrefs.DEFAULT_SECONDS)
        assertTrue(AutoTurnPrefs.DEFAULT_SECONDS in AutoTurnPrefs.LADDER)
        assertEquals(10, AutoTurnPrefs.step(8, 1))
        assertEquals(6, AutoTurnPrefs.step(8, -1))
        assertEquals(2, AutoTurnPrefs.step(2, -1))
        assertEquals(120, AutoTurnPrefs.step(120, 1))
        // The slider runs slow to fast: its first stop is the longest wait, its last the shortest.
        assertEquals(120, AutoTurnPrefs.secondsAt(0))
        assertEquals(2, AutoTurnPrefs.secondsAt(AutoTurnPrefs.SPEEDS - 1))
        assertEquals(8, AutoTurnPrefs.secondsAt(AutoTurnPrefs.speedOf(8)))
        assertTrue(AutoTurnPrefs.speedOf(6) > AutoTurnPrefs.speedOf(8))
        // Finer where a second matters, coarser above.
        val gaps = AutoTurnPrefs.LADDER.toList().zipWithNext { a, b -> b - a }
        assertEquals(gaps.sorted(), gaps)
    }

    @Test fun endingOrChangingTheBookClearsTheTimerAndTheChapterStop() {
        ReadAloudPlayback.set(ReadAloudState.PLAYING, "one", 0..5)
        ReadAloudPlayback.sleepTimer(SleepTimer.State(30, 1_800_000L))
        ReadAloudPlayback.sleepAtChapterEnd(true)
        ReadAloudPlayback.languages(setOf(Locale.US))
        ReadAloudPlayback.set(ReadAloudState.PAUSED, "one", 0..5)
        assertEquals(SleepTimer.State(30, 1_800_000L), ReadAloudPlayback.sleepTimer.value)
        assertTrue(ReadAloudPlayback.sleepAtChapterEnd.value)

        ReadAloudPlayback.set(ReadAloudState.LOADING, "two", null)
        assertNull(ReadAloudPlayback.sleepTimer.value)
        assertFalse(ReadAloudPlayback.sleepAtChapterEnd.value)
        assertTrue(ReadAloudPlayback.languages.value.isEmpty())

        ReadAloudPlayback.sleepTimer(SleepTimer.State(15, 900_000L))
        ReadAloudPlayback.set(ReadAloudState.IDLE, null, null)
        assertNull(ReadAloudPlayback.sleepTimer.value)
        assertNull(ReadAloudPlayback.bookId.value)
    }

    @Test fun aLanguageIsSayableWhenTheEngineListsAVoiceForItOnThePhone() {
        val voices = listOf(
            TtsLanguages.VoiceInfo(Locale.forLanguageTag("en-US"), onPhone = true),
            TtsLanguages.VoiceInfo(Locale.forLanguageTag("sv-SE"), onPhone = false),
        )
        assertEquals(TtsLanguages.State.INSTALLED, TtsLanguages.stateOf(Locale.UK, voices, null))
        assertEquals(TtsLanguages.State.DOWNLOAD, TtsLanguages.stateOf(LanguageDetector.SWEDISH, voices, null))
        assertEquals(TtsLanguages.State.NONE, TtsLanguages.stateOf(LanguageDetector.HEBREW, voices, null))
        // An engine that lists nothing is taken at its word.
        assertEquals(TtsLanguages.State.INSTALLED, TtsLanguages.stateOf(Locale.US, emptyList(), 0))
        assertEquals(TtsLanguages.State.NONE, TtsLanguages.stateOf(Locale.US, emptyList(), -2))
    }

    @Test fun voicesAreNamedByRegionAndNumberedOnlyWhenARegionHasSeveral() {
        val labels = TtsLanguages.voiceLabels(listOf(Locale.US, Locale.US, Locale.UK))
        assertEquals(listOf("English (United States) 1", "English (United States) 2", "English (United Kingdom)"), labels)
    }

    @Test fun aLatinBooksLanguageCanBeChosenAndTakesTheGuesssPlace() {
        val swedishBook = setOf(LanguageDetector.SWEDISH)
        assertTrue(isLatinBook(swedishBook))
        assertTrue(isLatinBook(setOf(LanguageDetector.ENGLISH, LanguageDetector.FRENCH)))
        assertFalse(isLatinBook(setOf(LanguageDetector.CHINESE, LanguageDetector.ENGLISH)))
        assertFalse(isLatinBook(emptySet()))
        assertEquals(listOf(LanguageDetector.NORWEGIAN), languagesShown(swedishBook, LanguageDetector.NORWEGIAN))
        // A Chinese book with English in it lists both, in the settings' order, until a choice replaces the English.
        val mixed = setOf(LanguageDetector.ENGLISH, LanguageDetector.CHINESE)
        assertEquals(listOf(LanguageDetector.ENGLISH, LanguageDetector.CHINESE), languagesShown(mixed, null))
        assertEquals(listOf(LanguageDetector.CHINESE, LanguageDetector.GERMAN), languagesShown(mixed, LanguageDetector.GERMAN))
    }

    @Test fun theCopiedDetectorReadsEachSentenceInItsOwnLanguage() {
        assertEquals(LanguageDetector.CHINESE, LanguageDetector.detect("他说：“走吧。”"))
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.detect("It was the best of times."))
        val swedish = "Det var en gång en flicka som hette Pippi. Hon bodde i en villa och hade en häst och en apa. " +
            "Hon var inte som andra barn, och hon kunde göra vad hon ville. Men det var något som hon inte hade. ".repeat(10)
        assertEquals(LanguageDetector.SWEDISH, LanguageDetector.survey(swedish).latin)
        assertTrue(LanguageDetector.SWEDISH in LanguageDetector.LATIN_LANGUAGES)
        assertFalse(LanguageDetector.CHINESE in LanguageDetector.LATIN_LANGUAGES)
    }
}
