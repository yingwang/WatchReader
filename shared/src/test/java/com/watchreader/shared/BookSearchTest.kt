package com.watchreader.shared

import com.watchreader.shared.search.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BookSearchTest {
    @Test fun matchesChineseAndCaseInsensitiveLatinAtOriginalOffsets() {
        val text = "雨后\n山青。Rain and RAIN 🌿山青"
        assertEquals(listOf(3, text.lastIndexOf("山青")), BookSearch.find(text, "山青").hits.map { it.start })
        assertEquals(listOf(text.indexOf("Rain"), text.indexOf("RAIN")), BookSearch.find(text, " rain ").hits.map { it.start })
        assertTrue(BookSearch.find(text, "   ").hits.isEmpty())
    }

    @Test fun overlappingMatchesAndLimitAreReportedAccurately() {
        assertEquals(listOf(0, 1, 2), BookSearch.find("aaaa", "aa").hits.map { it.start })
        assertFalse(BookSearch.find("aa", "a", 2).hasMore)
        val capped = BookSearch.find("aaa", "a", 2)
        assertEquals(2, capped.hits.size)
        assertTrue(capped.hasMore)
    }

    @Test fun excerptsHighlightExactMatchesAndDoNotSplitEmoji() {
        val text = "🌿".repeat(80) + "needle\nline" + "🌿".repeat(80)
        val hit = BookSearch.find(text, "needle\nline").hits.single()
        assertEquals("needle line", hit.excerpt.substring(hit.matchStart, hit.matchEnd))
        assertEquals("needle\nline", text.substring(hit.start, hit.end))
        assertTrue(BookSearch.find("🌿", "\uDF3F").hits.isEmpty())
        assertTrue(TextExcerpt.bookmark("🌿".repeat(100), 0).endsWith("…"))
    }

    @Test fun longSearchHonoursCancellation() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            BookSearch.find("x".repeat(100_000), "not found", checkpoint = {
                if (++checks == 3) throw CancellationException("Query changed")
            })
        }
        assertEquals(3, checks)
    }

    @Test fun changedOrClearedQueryRejectsClicksFromOldResults() = runBlocking {
        val search = ReaderSearch(this, Dispatchers.Unconfined, debounceMillis = 0)
        search.search("first second", "first")
        yield()
        val old = search.state.value
        val hit = old.results.hits.single()
        assertEquals(0, search.selectedOffset(old.generation, hit))
        search.search("first second", "second")
        assertNull(search.selectedOffset(old.generation, hit))
        yield()
        val current = search.state.value
        assertEquals(6, search.selectedOffset(current.generation, current.results.hits.single()))
        search.clear()
        assertNull(search.selectedOffset(current.generation, current.results.hits.single()))
    }
}
