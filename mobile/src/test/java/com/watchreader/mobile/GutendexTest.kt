package com.watchreader.mobile

import com.watchreader.mobile.data.model.FreeBookFile
import com.watchreader.mobile.data.repository.Gutendex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class GutendexTest {
    @Test
    fun aPageIsReadWithItsBooksInOrderAndItsNextLink() {
        val page = Gutendex.parsePage(POPULAR_PAGE)
        assertEquals(63177, page.total)
        assertEquals("https://gutendex.com/books/?languages=en&page=2", page.next)
        assertEquals(listOf(1342, 11, 61234), page.books.map { it.id })

        val pride = page.books[0]
        assertEquals("Pride and Prejudice", pride.title)
        assertEquals(listOf("Jane Austen"), pride.authors)
        assertEquals(listOf("en"), pride.languages)
        assertEquals("Courtship -- Fiction", pride.subjects.first())
        assertEquals(190246, pride.downloadCount)
        assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", pride.coverUrl)
    }

    @Test
    fun audioBooksCopyrightedBooksAndBooksWithNothingToReadAreLeftOut() {
        val ids = Gutendex.parsePage(POPULAR_PAGE).books.map { it.id }
        // 5200 is in copyright, 19159 is an audio book, 70001 has only a web page.
        assertEquals(listOf(1342, 11, 61234), ids)
    }

    @Test
    fun theEpubWithoutImagesComesFirstAndPlainTextLast() {
        val alice = Gutendex.parsePage(POPULAR_PAGE).books.first { it.id == 11 }
        assertEquals(
            listOf(
                FreeBookFile("https://www.gutenberg.org/ebooks/11.epub.noimages", isEpub = true),
                FreeBookFile("https://www.gutenberg.org/ebooks/11.epub3.images", isEpub = true),
                // Gutenberg sends .txt.utf-8 on to plain http, so the file it ends at is asked for directly.
                FreeBookFile("https://www.gutenberg.org/cache/epub/11/pg11.txt", isEpub = false),
                FreeBookFile("https://www.gutenberg.org/files/11/11-0.txt", isEpub = false),
            ),
            alice.files,
        )
    }

    @Test
    fun aBookWithOnlyPlainTextIsFetchedOverHttpsAndNeverAsAZip() {
        val book = Gutendex.parsePage(POPULAR_PAGE).books.first { it.id == 61234 }
        assertEquals(listOf(FreeBookFile("https://www.gutenberg.org/files/61234/61234-8.txt", isEpub = false)), book.files)
        assertNull(book.coverUrl)
        assertEquals(listOf("fi"), book.languages)
    }

    @Test
    fun anEpubElsewhereIsTakenAsItIs() {
        val files = Gutendex.filesFor(
            mapOf(
                "application/epub+zip" to "http://example.org/books/9.epub",
                "text/plain" to "https://example.org/books/9.txt",
            ),
        )
        assertEquals(
            listOf(
                FreeBookFile("https://example.org/books/9.epub", isEpub = true),
                FreeBookFile("https://example.org/books/9.txt", isEpub = false),
            ),
            files,
        )
        assertEquals(emptyList<FreeBookFile>(), Gutendex.filesFor(mapOf("text/html" to "https://www.gutenberg.org/ebooks/9.html.images")))
    }

    @Test
    fun onlyGutendexOwnNextLinksAreFollowed() {
        fun next(link: String?) = Gutendex.parsePage(JSONObject().put("count", 0).put("next", link ?: JSONObject.NULL).put("results", org.json.JSONArray()).toString()).next
        assertEquals("https://gutendex.com/books/?page=3", next("http://gutendex.com/books/?page=3"))
        assertNull(next("https://example.org/books/?page=3"))
        assertNull(next(null))
    }

    @Test
    fun aMissingResultsListIsAnEmptyPage() {
        val page = Gutendex.parsePage("""{"detail": "Invalid page."}""")
        assertEquals(0, page.books.size)
        assertNull(page.next)
    }

    @Test
    fun catalogueNamesReadAsNames() {
        assertEquals("Jane Austen", Gutendex.displayName("Austen, Jane"))
        assertEquals("F. Scott Fitzgerald", Gutendex.displayName("Fitzgerald, F. Scott (Francis Scott)"))
        assertEquals("Homer", Gutendex.displayName("Homer"))
        assertEquals("Leo Tolstoy", Gutendex.displayName("Tolstoy, Leo, graf"))
        assertEquals("Marcus Aurelius", Gutendex.displayName("Marcus Aurelius, Emperor of Rome"))
        assertEquals("Sun Tzu", Gutendex.displayName("Sun Tzu, active 6th century B.C."))
        assertEquals("Martin Luther King Jr.", Gutendex.displayName("King, Martin Luther, Jr."))
        assertEquals("Johann Wolfgang von Goethe", Gutendex.displayName("Goethe, Johann Wolfgang von"))
    }

    @Test
    fun catalogueTitlesReadAsTitles() {
        assertEquals("Kaksi morsianta: Yksinäytöksinen pila", Gutendex.cleanTitle("Kaksi morsianta : \$b Yksinäytöksinen pila"))
        assertEquals("Moby Dick; Or, The Whale", Gutendex.cleanTitle("Moby Dick;\r\nOr, The Whale"))
        assertEquals("The Prince: A Translation", Gutendex.cleanTitle("The Prince\r\nA Translation"))
        assertEquals("Middlemarch", Gutendex.cleanTitle("  Middlemarch \n"))
        // French sets a space before its colon, and it is left alone.
        assertEquals("Roméo et Juliette : Tragédie", Gutendex.cleanTitle("Roméo et Juliette : Tragédie"))
    }

    @Test
    fun addressesSayNoMoreThanTheyMust() {
        assertEquals("https://gutendex.com/books/", Gutendex.pageUrl("", null))
        assertEquals("https://gutendex.com/books/?languages=en", Gutendex.pageUrl("  ", "en"))
        assertEquals("https://gutendex.com/books/?search=pride+and+prejudice", Gutendex.pageUrl(" Pride  and Prejudice ", null))
        assertEquals("https://gutendex.com/books/?languages=fr&search=hugo", Gutendex.pageUrl("Hugo", "fr"))
        assertEquals("https://gutendex.com/books/?languages=zh&search=%E7%BA%A2%E6%A5%BC%E6%A2%A6", Gutendex.pageUrl("红楼梦", "zh"))
    }

    @Test
    fun thePhonesLanguageIsTheFilterWhenGutenbergHasIt() {
        assertEquals("en", Gutendex.languageFor(Locale.US))
        assertEquals("zh", Gutendex.languageFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals("no", Gutendex.languageFor(Locale.forLanguageTag("nb-NO")))
        assertEquals("sv", Gutendex.languageFor(Locale.forLanguageTag("sv-SE")))
        assertNull(Gutendex.languageFor(Locale.forLanguageTag("ko-KR")))
    }

    private companion object {
        /**
         * A page of https://gutendex.com/books/?languages=en as recorded, cut to three books with
         * their summaries and most shelves left out, and three more written in its shape for cases
         * a single page seldom shows: an audio book, a book with nothing but a web page, and an
         * old Finnish record with no author, no cover and only Latin-1 text.
         */
        val POPULAR_PAGE = """
            {
              "count": 63177,
              "next": "https://gutendex.com/books/?languages=en&page=2",
              "previous": null,
              "results": [
                {
                  "id": 1342,
                  "title": "Pride and Prejudice",
                  "authors": [{"name": "Austen, Jane", "birth_year": 1775, "death_year": 1817}],
                  "summaries": [],
                  "editors": [],
                  "translators": [],
                  "subjects": ["Courtship -- Fiction", "Domestic fiction", "England -- Fiction", "Love stories", "Sisters -- Fiction", "Social classes -- Fiction", "Young women -- Fiction"],
                  "bookshelves": ["Best Books Ever Listings"],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Text",
                  "formats": {
                    "text/html": "https://www.gutenberg.org/ebooks/1342.html.images",
                    "application/epub+zip": "https://www.gutenberg.org/ebooks/1342.epub3.images",
                    "application/x-mobipocket-ebook": "https://www.gutenberg.org/ebooks/1342.kf8.images",
                    "application/rdf+xml": "https://www.gutenberg.org/ebooks/1342.rdf",
                    "image/jpeg": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                    "application/octet-stream": "https://www.gutenberg.org/cache/epub/1342/pg1342-h.zip",
                    "text/plain; charset=utf-8": "https://www.gutenberg.org/ebooks/1342.txt.utf-8"
                  },
                  "download_count": 190246
                },
                {
                  "id": 11,
                  "title": "Alice's Adventures in Wonderland",
                  "authors": [{"name": "Carroll, Lewis", "birth_year": 1832, "death_year": 1898}],
                  "summaries": [],
                  "editors": [],
                  "translators": [],
                  "subjects": ["Alice (Fictitious character from Carroll) -- Juvenile fiction", "Children's stories", "Fantasy fiction", "Imaginary places -- Juvenile fiction"],
                  "bookshelves": ["Category: British Literature"],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Text",
                  "formats": {
                    "text/html": "https://www.gutenberg.org/ebooks/11.html.images",
                    "application/epub+zip": "https://www.gutenberg.org/ebooks/11.epub3.images",
                    "application/x-mobipocket-ebook": "https://www.gutenberg.org/ebooks/11.kf8.images",
                    "application/rdf+xml": "https://www.gutenberg.org/ebooks/11.rdf",
                    "image/jpeg": "https://www.gutenberg.org/cache/epub/11/pg11.cover.medium.jpg",
                    "application/octet-stream": "https://www.gutenberg.org/cache/epub/11/pg11-h.zip",
                    "text/plain; charset=utf-8": "https://www.gutenberg.org/ebooks/11.txt.utf-8",
                    "text/plain; charset=us-ascii": "https://www.gutenberg.org/files/11/11-0.txt"
                  },
                  "download_count": 121623
                },
                {
                  "id": 5200,
                  "title": "Metamorphosis",
                  "authors": [{"name": "Kafka, Franz", "birth_year": 1883, "death_year": 1924}],
                  "summaries": [],
                  "editors": [],
                  "translators": [{"name": "Wyllie, David (Translator)", "birth_year": null, "death_year": null}],
                  "subjects": ["Metamorphosis -- Fiction", "Psychological fiction"],
                  "bookshelves": ["Category: Classics of Literature"],
                  "languages": ["en"],
                  "copyright": true,
                  "media_type": "Text",
                  "formats": {
                    "application/epub+zip": "https://www.gutenberg.org/ebooks/5200.epub3.images",
                    "image/jpeg": "https://www.gutenberg.org/cache/epub/5200/pg5200.cover.medium.jpg",
                    "text/plain; charset=utf-8": "https://www.gutenberg.org/ebooks/5200.txt.utf-8"
                  },
                  "download_count": 47449
                },
                {
                  "id": 19159,
                  "title": "Alice's Adventures in Wonderland",
                  "authors": [{"name": "Carroll, Lewis", "birth_year": 1832, "death_year": 1898}],
                  "summaries": [],
                  "editors": [],
                  "translators": [],
                  "subjects": ["Fantasy fiction"],
                  "bookshelves": [],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Sound",
                  "formats": {
                    "audio/mpeg": "https://www.gutenberg.org/files/19159/mp3/19159-01.mp3",
                    "text/plain": "https://www.gutenberg.org/files/19159/19159-readme.txt"
                  },
                  "download_count": 900
                },
                {
                  "id": 61234,
                  "title": "Kaksi morsianta : ${'$'}b Yksinäytöksinen pila",
                  "authors": [],
                  "summaries": [],
                  "editors": [],
                  "translators": [],
                  "subjects": [],
                  "bookshelves": [],
                  "languages": ["fi"],
                  "copyright": null,
                  "media_type": "Text",
                  "formats": {
                    "text/plain; charset=iso-8859-1": "http://www.gutenberg.org/files/61234/61234-8.txt",
                    "text/plain": "https://www.gutenberg.org/files/61234/61234-8.zip"
                  },
                  "download_count": 3100
                },
                {
                  "id": 70001,
                  "title": "A Page of Pictures",
                  "authors": [],
                  "summaries": [],
                  "editors": [],
                  "translators": [],
                  "subjects": [],
                  "bookshelves": [],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Text",
                  "formats": {"text/html": "https://www.gutenberg.org/ebooks/70001.html.images"},
                  "download_count": 10
                }
              ]
            }
        """.trimIndent()
    }
}
