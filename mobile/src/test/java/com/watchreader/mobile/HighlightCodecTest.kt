package com.watchreader.mobile

import com.watchreader.mobile.highlight.Highlight
import com.watchreader.mobile.highlight.HighlightCodec
import com.watchreader.mobile.highlight.HighlightColor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightCodecTest {
    private val first = Highlight("a", 10, 25, "a truth universally", HighlightColor.GREEN, "", 1_000, 1_000)
    private val second = Highlight("b", 300, 320, "第一章 开端", HighlightColor.PINK, "A note\nover two lines, with \"quotes\"", 2_000, 5_000)

    @Test fun everyFieldSurvivesTheRoundTrip() {
        val json = HighlightCodec.encode("book-1", listOf(second, first))
        assertEquals(listOf(first, second), HighlightCodec.decode(json))
        val root = JSONObject(json)
        assertEquals(HighlightCodec.VERSION, root.getInt("version"))
        assertEquals("book-1", root.getString("bookId"))
    }

    @Test fun anEmptyNoteIsLeftOutOfTheFile() {
        val record = JSONObject(HighlightCodec.encode("book", listOf(first))).getJSONArray("highlights").getJSONObject(0)
        assertTrue(!record.has("note"))
    }

    @Test fun fieldsItDoesNotKnowAreIgnored() {
        val json = """
            {"version":1,"bookId":"x","syncedAt":123,"highlights":[
              {"id":"a","start":10,"end":25,"quote":"q","color":"green","note":"n","created":1,"updated":2,
               "style":"underline","tags":["later"]}
            ]}
        """.trimIndent()
        assertEquals(listOf(Highlight("a", 10, 25, "q", HighlightColor.GREEN, "n", 1, 2)), HighlightCodec.decode(json))
    }

    @Test fun missingFieldsTakePlainDefaults() {
        val json = """{"highlights":[{"id":"a","start":4,"end":9,"created":7},{"id":"b","start":1,"end":2,"color":"purple"}]}"""
        val read = HighlightCodec.decode(json)
        assertEquals(Highlight("b", 1, 2, "", HighlightColor.YELLOW, "", 0, 0), read[0])
        // A highlight never changed was last updated when it was made.
        assertEquals(Highlight("a", 4, 9, "", HighlightColor.YELLOW, "", 7, 7), read[1])
    }

    @Test fun recordsWithoutAPlaceInTheBookAreSkipped() {
        val json = """
            {"version":1,"highlights":[
              {"id":"no-end","start":4},
              {"id":"backwards","start":9,"end":4},
              {"id":"","start":1,"end":2},
              {"start":1,"end":2},
              "not an object",
              {"id":"kept","start":1,"end":2}
            ]}
        """.trimIndent()
        assertEquals(listOf("kept"), HighlightCodec.decode(json).map { it.id })
    }

    @Test fun aFileWithoutAListHoldsNoHighlights() {
        assertEquals(emptyList<Highlight>(), HighlightCodec.decode("""{"version":1}"""))
    }

    @Test fun aRepeatedIdKeepsTheLaterRecord() {
        val json = """{"highlights":[{"id":"a","start":1,"end":2,"note":"old"},{"id":"a","start":1,"end":2,"note":"new"}]}"""
        assertEquals(listOf("new"), HighlightCodec.decode(json).map { it.note })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNewerFormatIsRefusedRatherThanReadAsEmpty() {
        HighlightCodec.decode("""{"version":2,"highlights":[]}""")
    }

    @Test(expected = Exception::class)
    fun aDamagedFileIsRefused() {
        HighlightCodec.decode("""{"version":1,"highlights":[{"id":"a","sta""")
    }
}
