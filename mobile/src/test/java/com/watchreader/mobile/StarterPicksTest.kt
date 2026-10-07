package com.watchreader.mobile

import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.data.repository.GutenbergCatalog
import com.watchreader.mobile.data.repository.StarterShelf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free classics a new library is offered, and which libraries count as new. */
class StarterPicksTest {
    private val english = StarterShelf.CURATED.getValue("en")

    private val catalan = listOf(
        FreeBook(56856, "L'auca del senyor Esteve", "Santiago Rusiñol"),
        FreeBook(29944, "Tres Homes Dins D'una Barca", "Jerome K. Jerome"),
        FreeBook(30890, "Les Aventures De Tom Sawyer", "Mark Twain"),
        FreeBook(76140, "L'esca del pecat", "M. Figuerola Aldroféu"),
    )

    /** Gutenberg's fiction by language, noting each language asked for. */
    private class Catalogue(private val lists: Map<String, List<FreeBook>> = emptyMap()) {
        val asked = mutableListOf<String>()
        suspend fun fiction(language: String): List<FreeBook> {
            asked += language
            return lists[language].orEmpty()
        }
    }

    @Test
    fun aLanguageChosenForByHandGetsItsOwnThreeWithoutAskingGutenberg() = runBlocking {
        val catalogue = Catalogue()
        assertEquals(listOf(35312, 22367, 2499), StarterShelf.picks("de", catalogue::fiction).map { it.id })
        assertEquals(listOf(23962, 24264, 23950), StarterShelf.picks("zh", catalogue::fiction).map { it.id })
        assertEquals(english, StarterShelf.picks("en", catalogue::fiction))
        assertEquals(emptyList<String>(), catalogue.asked)
    }

    @Test
    fun everyLanguageAskedForIsCoveredWithThreeDifferentBooks() = runBlocking {
        val wanted = listOf("en", "de", "fr", "es", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "cs", "hu", "ru", "zh", "ja", "el")
        assertEquals(wanted.toSet(), StarterShelf.CURATED.keys)
        for (language in wanted) {
            // Each code is one the phone's language can turn into, or the shelf would never be reached.
            assertTrue(language, language in GutenbergCatalog.LANGUAGES)
            val own = StarterShelf.CURATED.getValue(language)
            assertTrue(language, own.size <= StarterShelf.COUNT)
            assertEquals(language, own.size, own.distinctBy { it.id }.size)
            val picks = StarterShelf.picks(language) { error("$language should not be looked up") }
            assertEquals(language, StarterShelf.COUNT, picks.distinctBy { it.id }.size)
            assertTrue(language, picks.all { it.title.isNotBlank() && !it.author.isNullOrBlank() })
        }
    }

    @Test
    fun aLanguageWithTooFewOfItsOwnIsToppedUpFromTheEnglish() = runBlocking {
        // Gutenberg has no Russian book worth a first read, so Russian is all English.
        assertEquals(english, StarterShelf.picks("ru") { error("Russian should not be looked up") })
    }

    @Test
    fun aPhoneInALanguageGutenbergLacksIsOfferedTheEnglishThree() = runBlocking {
        val catalogue = Catalogue()
        assertEquals(english, StarterShelf.picks(null, catalogue::fiction))
        assertEquals(emptyList<String>(), catalogue.asked)
    }

    @Test
    fun anyOtherLanguageIsOfferedItsMostDownloadedFiction() = runBlocking {
        val catalogue = Catalogue(mapOf("ca" to catalan))
        assertEquals(catalan.take(3), StarterShelf.picks("ca", catalogue::fiction))
        assertEquals(listOf("ca"), catalogue.asked)
        // A book listed twice counts once.
        val repeated = Catalogue(mapOf("ca" to listOf(catalan[0], catalan[0], catalan[1], catalan[2])))
        assertEquals(catalan.take(3), StarterShelf.picks("ca", repeated::fiction))
    }

    @Test
    fun tooLittleFictionForAFullCardIsOfferedTheEnglishThreeInstead() = runBlocking {
        val short = Catalogue(mapOf("cy" to catalan.take(2)))
        assertEquals(english, StarterShelf.picks("cy", short::fiction))
        assertEquals(listOf("cy"), short.asked)
        // Two different books are too few, even listed three times.
        val repeated = Catalogue(mapOf("eo" to listOf(catalan[0], catalan[1], catalan[0])))
        assertEquals(english, StarterShelf.picks("eo", repeated::fiction))
        assertEquals(english, StarterShelf.picks("tl", Catalogue()::fiction))
    }

    @Test
    fun theFictionSearchIsGutenbergsOwnQuery() {
        assertEquals(
            "https://www.gutenberg.org/ebooks/search.opds/?query=s.fiction+l.ca+%21cat.audio&sort_order=downloads",
            GutenbergCatalog.searchUrl(StarterShelf.FICTION, "ca"),
        )
    }

    @Test
    fun theGuideAloneIsNotABookOfTheReadersOwn() {
        fun book(id: String) = Book(id = id, title = id, filePath = "/books/$id.txt", sizeBytes = 1, addedEpochMs = 0)
        assertFalse(BookRepository.hasOwnBooks(emptyList()))
        assertFalse(BookRepository.hasOwnBooks(listOf(book("sample"))))
        assertTrue(BookRepository.hasOwnBooks(listOf(book("sample"), book("2b1f6c1e-own"))))
        assertTrue(BookRepository.hasOwnBooks(listOf(book("2b1f6c1e-own"))))
    }
}
