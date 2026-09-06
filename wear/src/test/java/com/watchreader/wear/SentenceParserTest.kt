package com.watchreader.wear

import com.watchreader.wear.tts.SentenceParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceParserTest {
    @Test
    fun englishFullStopsEndSentences() {
        val text = "It was the best of times. It was the worst of times. \"Really?\" she said."
        assertEquals(
            listOf("It was the best of times.", "It was the worst of times.", "\"Really?\"", "she said."),
            SentenceParser.split(text),
        )
    }

    @Test
    fun abbreviationsInitialsAndNumbersStayWhole() {
        val text = "Mr. Darcy met J. K. Rowling at 5 p.m. on No. 3 Baker St. with pi at 3.14 (e.g. today). Then he left."
        assertEquals(
            listOf("Mr. Darcy met J. K. Rowling at 5 p.m. on No. 3 Baker St. with pi at 3.14 (e.g. today).", "Then he left."),
            SentenceParser.split(text),
        )
    }

    @Test
    fun aFullStopInsideAWordOrAddressIsNotAnEnd() {
        assertEquals(listOf("See www.example.com/page.html for details."), SentenceParser.split("See www.example.com/page.html for details."))
    }

    @Test
    fun closingQuotesStayWithTheirSentenceAndRangesMatchTheText() {
        val text = "他说：“走吧。”她没有动。\nThe end."
        val ranges = SentenceParser.ranges(text)
        assertEquals(listOf("他说：“走吧。”", "她没有动。", "The end."), ranges.map { text.substring(it.first, it.last + 1) })
        assertEquals(ranges.map { text.substring(it.first, it.last + 1) }, SentenceParser.split(text))
    }

    @Test
    fun splittingStartsFromTheGivenOffset() {
        val text = "First one. Second one. Third one."
        val from = text.indexOf("Second")
        assertEquals(listOf("Second one.", "Third one."), SentenceParser.splitWithRanges(text, from).map { it.first })
        assertEquals(from, SentenceParser.ranges(text, from).first().first)
    }
}
