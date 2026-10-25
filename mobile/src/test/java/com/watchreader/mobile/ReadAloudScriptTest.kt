package com.watchreader.mobile

import com.watchreader.mobile.tts.ReadAloudScript
import com.watchreader.mobile.tts.ReadAloudScript.Position
import com.watchreader.mobile.tts.SentenceParser
import com.watchreader.shared.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudScriptTest {
    private val english = "It was the best of times. It was the worst of times. Mr. Darcy met J. K. Rowling at 5 p.m. today.\n\n\"Really?\" she said."
    private val chinese = "第一章\n他说：“走吧。”她没有动。天快亮了！\n第二章\n雨停了。"

    private fun ReadAloudScript.all() = sentences.map { words(it) }

    @Test fun latinTextSplitsAtFullStopsButNotAtAbbreviationsOrInitials() {
        val script = ReadAloudScript.of(english)
        assertEquals(
            listOf(
                "It was the best of times.",
                "It was the worst of times.",
                "Mr. Darcy met J. K. Rowling at 5 p.m. today.",
                "\"Really?\"",
                "she said.",
            ),
            script.all(),
        )
    }

    @Test fun chineseTextSplitsAtItsOwnStopsAndKeepsClosingQuotes() {
        val script = ReadAloudScript.of(chinese)
        assertEquals(listOf("第一章", "他说：“走吧。”", "她没有动。", "天快亮了！", "第二章", "雨停了。"), script.all())
    }

    @Test fun everySentenceIsItsOwnPlaceInTheBook() {
        for (text in listOf(english, chinese)) {
            val script = ReadAloudScript.of(text)
            var previousEnd = -1
            for (range in script.sentences) {
                // Each range is the sentence's characters in the book's own text, in order, never overlapping.
                assertTrue(range.first > previousEnd && range.last < text.length)
                assertEquals(text.substring(range.first, range.last + 1), script.words(range))
                assertTrue(!text[range.first].isWhitespace() && !text[range.last].isWhitespace())
                previousEnd = range.last
            }
        }
    }

    @Test fun aLongRunWithoutPunctuationIsNeverCutInsideAPair() {
        // Rare ideographs (U+20000) and emoji with no stop or comma anywhere: the cut that keeps
        // an utterance short must fall between characters, not between the halves of one.
        for (text in listOf("𠀀".repeat(450), "字" + "😀".repeat(450))) {
            val script = ReadAloudScript.of(text)
            assertTrue(script.size > 1)
            for (range in script.sentences) {
                assertTrue("starts inside a pair at ${range.first}", !text[range.first].isLowSurrogate())
                assertTrue("ends inside a pair at ${range.last}", !text[range.last].isHighSurrogate())
            }
            assertEquals(text, script.all().joinToString(""))
        }
    }

    @Test fun aQuoteOpeningALineStaysWithItsLine() {
        val text = "He waited.\n\"Come in,\" she said.\n“好。”\n"
        assertEquals(listOf("He waited.", "\"Come in,\" she said.", "“好。”"), SentenceParser.split(text))
    }

    @Test fun readingFromASentenceStartBeginsWithTheWholeSentence() {
        val script = ReadAloudScript.of(english)
        val second = english.indexOf("It was the worst")
        assertEquals(Position(1, second), script.position(second))
        assertEquals(Position(0, 0), script.position(0))
        assertEquals("It was the worst of times.", script.words(script.spoken(1, script.position(second))))
    }

    @Test fun readingFromTheMiddleOfASentenceBeginsAtThePagesFirstWord() {
        val script = ReadAloudScript.of(english)
        val darcy = english.indexOf("Darcy")
        val start = script.position(darcy)
        assertEquals(Position(2, darcy), start)
        assertEquals("Darcy met J. K. Rowling at 5 p.m. today.", script.words(script.spoken(2, start)))
        // Only that sentence is cut; the ones after it are read whole.
        assertEquals(script.sentences[3], script.spoken(3, start))
        // A page that starts on the space before a word starts at the word.
        assertEquals(Position(2, darcy), script.position(darcy - 1))
    }

    @Test fun readingFromTheMiddleOfAChineseSentenceBeginsAtThatCharacter() {
        val script = ReadAloudScript.of(chinese)
        val at = chinese.indexOf("没有")
        val start = script.position(at)
        assertEquals(2, start.index)
        assertEquals("没有动。", script.words(script.spoken(start.index, start)))
    }

    @Test fun theGapBetweenSentencesAndATrailingQuoteGoOnToTheNextSentence() {
        val script = ReadAloudScript.of(english)
        val blank = english.indexOf("\n\n") + 1
        assertEquals(Position(3, english.indexOf("\"Really")), script.position(blank))
        // A page beginning with the closing quote of a sentence has nothing of that sentence to say.
        val quote = chinese.indexOf("”")
        val cjk = ReadAloudScript.of(chinese)
        assertEquals(Position(2, chinese.indexOf("她")), cjk.position(quote))
    }

    @Test fun pastTheLastSentenceThereIsNothingLeft() {
        val script = ReadAloudScript.of(english)
        assertEquals(script.size, script.position(english.length).index)
        assertEquals(script.size, script.position(english.length + 50).index)
        assertEquals(0, ReadAloudScript.of("").size)
        assertEquals(0, ReadAloudScript.of("").position(0).index)
    }

    @Test fun previousRestartsASentenceBegunPartWayAndOtherwiseGoesBackOne() {
        val script = ReadAloudScript.of(english)
        val darcy = english.indexOf("Darcy")
        val start = script.position(darcy)
        assertEquals(Position(2, script.sentences[2].first), script.previous(2, start))
        assertEquals(Position(1, script.sentences[1].first), script.previous(2, null))
        assertEquals(Position(0, 0), script.previous(0, null))
        assertEquals(Position(3, script.sentences[3].first), script.next(2))
        assertNull(script.next(script.size - 1))
    }

    @Test fun theChapterEndsAtTheFirstSentenceOfTheNextChapter() {
        val script = ReadAloudScript.of(chinese)
        val chapters = listOf(Chapter("第一章", 0), Chapter("第二章", chinese.indexOf("第二章")))
        // Anywhere in the first chapter, reading pauses before "第二章".
        assertEquals(4, script.chapterEnd(chapters, 0))
        assertEquals(4, script.chapterEnd(chapters, 3))
        // In the last chapter the end of the book is the end of the chapter.
        assertEquals(script.size, script.chapterEnd(chapters, 4))
        assertEquals(script.size, script.chapterEnd(emptyList(), 1))
        // The contents may come in any order.
        assertEquals(4, script.chapterEnd(chapters.reversed(), 1))
    }
}
