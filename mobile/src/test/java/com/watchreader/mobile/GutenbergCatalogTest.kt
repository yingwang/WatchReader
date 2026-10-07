package com.watchreader.mobile

import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.FreeBookFile
import com.watchreader.mobile.data.repository.GutenbergCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The feeds under src/test/resources/opds were recorded from www.gutenberg.org on 7 October 2026
 * and cut down to a few entries each, with the small icon every search entry carries shortened.
 */
class GutenbergCatalogTest {
    @Test
    fun searchResultsAreReadInOrderWithTheirNextPage() {
        val page = GutenbergCatalog.parseSearch(fixture("search-dickens.xml"))
        assertEquals(
            listOf(
                FreeBook(98, "A Tale of Two Cities", "Charles Dickens"),
                FreeBook(1400, "Great Expectations", "Charles Dickens"),
                // A book with no author has its download count where the author would be.
                FreeBook(25948, "Fifty-Two Stories For Girls", null),
            ),
            page.books,
        )
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=dickens&sort_order=downloads&start_index=26", page.next)
    }

    @Test
    fun titlesLoseTheirLanguageAndCatalogueSpacing() {
        val books = GutenbergCatalog.parseSearch(fixture("search-swedish.xml")).books
        assertEquals(
            listOf("Carl Svenske: Historisk berättelse från frihetstiden", "Jubelklangen: Andliga sånger", "Valda Berättelser"),
            books.map { it.title },
        )
        assertEquals(listOf("Gustaf Björlin", null, "Selma Lagerlöf"), books.map { it.author })
        assertEquals(
            "https://www.gutenberg.org/ebooks/search.opds/?query=l.sv&sort_order=downloads&start_index=26",
            GutenbergCatalog.parseSearch(fixture("search-swedish.xml")).next,
        )
    }

    @Test
    fun aSearchWithNoMatchesIsAnEmptyPage() {
        val page = GutenbergCatalog.parseSearch(fixture("search-nothing.xml"))
        assertEquals(emptyList<FreeBook>(), page.books)
        assertNull(page.next)
    }

    @Test
    fun onlyGutenbergsOwnSearchPagesAreFollowed() {
        fun next(href: String) = GutenbergCatalog.parseSearch(
            """<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="$href"/></feed>""",
        ).next
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=l.sv&start_index=26", next("http://www.gutenberg.org/ebooks/search.opds/?query=l.sv&amp;start_index=26"))
        assertNull(next("https://example.org/ebooks/search.opds/?start_index=26"))
        assertNull(next("/ebooks/11.opds"))
    }

    @Test
    fun aBooksFeedGivesItsLanguageSubjectsAndFilesBestFirst() {
        val alice = GutenbergCatalog.parseBook(fixture("book-11.xml"), 11)!!
        assertEquals(listOf("en"), alice.languages)
        assertEquals(
            listOf("Fantasy fiction", "Children's stories", "Imaginary places -- Juvenile fiction", "Alice (Fictitious character from Carroll) -- Juvenile fiction"),
            alice.subjects,
        )
        assertTrue(alice.publicDomain)
        assertEquals(
            listOf(
                FreeBookFile("https://www.gutenberg.org/ebooks/11.epub.noimages", isEpub = true),
                FreeBookFile("https://www.gutenberg.org/ebooks/11.epub3.images", isEpub = true),
                FreeBookFile("https://www.gutenberg.org/cache/epub/11/pg11.txt", isEpub = false),
            ),
            alice.files,
        )
    }

    @Test
    fun anEpubLargerThanAnImportTakesIsNotOffered() {
        // Pride and Prejudice with its illustrations is 25 MB; the import stops at 20.
        val pride = GutenbergCatalog.parseBook(fixture("book-1342.xml"), 1342)!!
        assertEquals(
            listOf(
                FreeBookFile("https://www.gutenberg.org/ebooks/1342.epub.noimages", isEpub = true),
                FreeBookFile("https://www.gutenberg.org/cache/epub/1342/pg1342.txt", isEpub = false),
            ),
            pride.files,
        )
    }

    @Test
    fun aBookStillInCopyrightIsNotPublicDomain() {
        val metamorphosis = GutenbergCatalog.parseBook(fixture("book-5200.xml"), 5200)!!
        assertFalse(metamorphosis.publicDomain)
        // A feed that says nothing about rights is not taken for public domain either.
        val unstated = GutenbergCatalog.parseBook(fixture("book-11.xml").replace(Regex("<rights>[^<]*</rights>"), ""), 11)!!
        assertFalse(unstated.publicDomain)
    }

    @Test
    fun aRecordingHasNothingToRead() {
        val sound = fixture("book-11.xml").replace("""term="Text"""", """term="Sound"""")
        assertEquals(emptyList<FreeBookFile>(), GutenbergCatalog.parseBook(sound, 11)!!.files)
    }

    @Test
    fun aFeedWithoutAnEntryIsNoBook() {
        assertNull(GutenbergCatalog.parseBook("""<feed xmlns="http://www.w3.org/2005/Atom"/>""", 11))
    }

    @Test
    fun catalogueTitlesReadAsTitles() {
        assertEquals("Ιλιάδα", GutenbergCatalog.cleanTitle("Ιλιάδα (Modern Greek (1453-))"))
        assertEquals("Diccionario Ingles-Español-Tagalog", GutenbergCatalog.cleanTitle("Diccionario Ingles-Español-Tagalog\r (Spanish) (Tagalog)"))
        assertEquals("The Odyssey", GutenbergCatalog.cleanTitle("The Odyssey\n"))
        assertEquals("Moby Dick; Or, The Whale", GutenbergCatalog.cleanTitle("Moby Dick;\r\nOr, The Whale"))
        assertEquals("The Prince: A Translation", GutenbergCatalog.cleanTitle("The Prince\r\nA Translation"))
        // Brackets that are part of the title stay, and so does French spacing before a colon.
        assertEquals("Oliver Twist, Vol. 2 (of 3)", GutenbergCatalog.cleanTitle("Oliver Twist, Vol. 2 (of 3)"))
        assertEquals("Roméo et Juliette : Tragédie", GutenbergCatalog.cleanTitle("Roméo et Juliette : Tragédie (French)"))
    }

    @Test
    fun searchesAndLanguagesGoInOneQuerySortedByDownloads() {
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=%21cat.audio&sort_order=downloads", GutenbergCatalog.searchUrl("  ", null))
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=l.sv+%21cat.audio&sort_order=downloads", GutenbergCatalog.searchUrl("", "sv"))
        assertEquals(
            "https://www.gutenberg.org/ebooks/search.opds/?query=tolstoy+war+%21cat.audio&sort_order=downloads",
            GutenbergCatalog.searchUrl(" tolstoy   war ", null),
        )
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=alice+l.fr+%21cat.audio&sort_order=downloads", GutenbergCatalog.searchUrl("alice", "fr"))
        assertEquals(
            "https://www.gutenberg.org/ebooks/search.opds/?query=%E7%BA%A2%E6%A5%BC%E6%A2%A6+l.zh+%21cat.audio&sort_order=downloads",
            GutenbergCatalog.searchUrl("红楼梦", "zh"),
        )
        assertEquals("https://www.gutenberg.org/ebooks/11.opds", GutenbergCatalog.bookUrl(11))
        assertEquals("https://www.gutenberg.org/cache/epub/11/pg11.cover.small.jpg", GutenbergCatalog.coverUrl(11, small = true))
    }

    @Test
    fun thePhonesLanguageIsTheFilterWhenGutenbergHasIt() {
        assertEquals("en", GutenbergCatalog.languageFor(Locale.US))
        assertEquals("zh", GutenbergCatalog.languageFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals("no", GutenbergCatalog.languageFor(Locale.forLanguageTag("nb-NO")))
        assertEquals("sv", GutenbergCatalog.languageFor(Locale.forLanguageTag("sv-SE")))
        assertNull(GutenbergCatalog.languageFor(Locale.forLanguageTag("ko-KR")))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/opds/$name")) { "Missing fixture $name" }.readText()
}
