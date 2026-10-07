package com.watchreader.mobile

import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.data.repository.GutenbergCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free classics a new library is offered, and which libraries count as new. */
class StarterPicksTest {
    private val english = listOf(
        FreeBook(1342, "Pride and Prejudice", "Jane Austen"),
        FreeBook(2701, "Moby Dick; Or, The Whale", "Herman Melville"),
        FreeBook(84, "Frankenstein; or, the modern prometheus", "Mary Wollstonecraft Shelley"),
        FreeBook(11, "Alice's Adventures in Wonderland", "Lewis Carroll"),
    )
    private val swedish = listOf(
        FreeBook(65580, "Carl Svenske: Historisk berättelse från frihetstiden", "Gustaf Björlin"),
        FreeBook(62806, "Kåtornas folk", "Ester Blenda Nordström"),
        FreeBook(26479, "I Utvecklingstid: En berättelse om flickor", "Toini Topelius"),
        FreeBook(51613, "Engelsk-Svensk och Svensk-Engelsk Ordbok", "Frederick Lönnkvist"),
    )

    /** The popular lists by language, null for all of them, noting each one asked for. */
    private class Catalogue(private val lists: Map<String?, List<FreeBook>>) {
        val asked = mutableListOf<String?>()
        suspend fun mostDownloaded(language: String?): List<FreeBook> {
            asked += language
            return lists[language].orEmpty()
        }
    }

    @Test
    fun aLanguageWithBooksEnoughGivesItsOwnThreeMostDownloaded() = runBlocking {
        val catalogue = Catalogue(mapOf("sv" to swedish, null to english))
        val picks = GutenbergCatalog.starterPicks("sv", catalogue::mostDownloaded)
        assertEquals(swedish.take(3), picks)
        // Every language's list is not asked for when the phone's own fills the card.
        assertEquals(listOf<String?>("sv"), catalogue.asked)
    }

    @Test
    fun aPhoneInALanguageGutenbergLacksIsOfferedEveryLanguage() = runBlocking {
        val catalogue = Catalogue(mapOf(null to english))
        assertEquals(english.take(3), GutenbergCatalog.starterPicks(null, catalogue::mostDownloaded))
        assertEquals(listOf<String?>(null), catalogue.asked)
    }

    @Test
    fun aLanguageWithTooFewBooksFallsBackToEveryLanguage() = runBlocking {
        val catalogue = Catalogue(mapOf("cy" to swedish.take(2), null to english))
        assertEquals(english.take(3), GutenbergCatalog.starterPicks("cy", catalogue::mostDownloaded))
        assertEquals(listOf("cy", null), catalogue.asked)

        val none = Catalogue(mapOf(null to english))
        assertEquals(english.take(3), GutenbergCatalog.starterPicks("tl", none::mostDownloaded))
    }

    @Test
    fun aBookListedTwiceIsOfferedOnce() = runBlocking {
        val repeated = listOf(swedish[0], swedish[0], swedish[1], swedish[2])
        val catalogue = Catalogue(mapOf("sv" to repeated, null to english))
        assertEquals(swedish.take(3), GutenbergCatalog.starterPicks("sv", catalogue::mostDownloaded))
        // Two different books are too few, even listed three times.
        val short = Catalogue(mapOf("sv" to listOf(swedish[0], swedish[1], swedish[0]), null to english))
        assertEquals(english.take(3), GutenbergCatalog.starterPicks("sv", short::mostDownloaded))
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
