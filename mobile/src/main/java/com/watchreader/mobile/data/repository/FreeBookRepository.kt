package com.watchreader.mobile.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.FreeBookDetails
import com.watchreader.mobile.data.model.FreeBookPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The free library answered, but not with what was asked for. */
class CatalogueException(message: String) : IOException(message)

/**
 * Project Gutenberg's public-domain books, found through its own catalogue feeds and added
 * through the same link import as any other book. Pages, books' details and covers are kept in
 * memory for the life of the process, so going back to a list already seen asks Gutenberg for
 * nothing; nothing is kept on disk.
 */
object FreeBookRepository {
    private val pages = LruCache<String, FreeBookPage>(32)

    private val bookDetails = LruCache<Int, FreeBookDetails>(64)

    /**
     * A list shows Gutenberg's small covers, 66 pixels wide, and a book's details its large ones,
     * 200 wide: room for hundreds of the first and some seventy of the second.
     */
    private val covers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** A list scrolled quickly asks for many covers at once; Gutenberg is asked for a few at a time. */
    private val coverSlots = Semaphore(4)

    /** The most downloaded books, or those whose title or author matches [search]. */
    suspend fun firstPage(search: String, language: String?): FreeBookPage = page(GutenbergCatalog.searchUrl(search, language))

    /** The page at [url], a page's next link. */
    suspend fun page(url: String): FreeBookPage {
        pages.get(url)?.let { return it }
        val xml = fetch(url, MAX_FEED_BYTES)
        val page = readFeed { GutenbergCatalog.parseSearch(xml) }
        pages.put(url, page)
        return page
    }

    /** A book's language, subjects, rights and files, from its own feed. */
    suspend fun details(book: FreeBook): FreeBookDetails {
        bookDetails.get(book.id)?.let { return it }
        val xml = fetch(GutenbergCatalog.bookUrl(book.id), MAX_FEED_BYTES)
        val found = readFeed { GutenbergCatalog.parseBook(xml, book.id) }
            ?: throw CatalogueException("The free library has no record of this book")
        bookDetails.put(book.id, found)
        return found
    }

    fun cachedCover(url: String): Bitmap? = covers.get(url)

    /** The cover at [url], scaled down to about [targetWidth] pixels; null when there is none to be had. */
    suspend fun cover(url: String, targetWidth: Int): Bitmap? {
        covers.get(url)?.let { return it }
        val bytes = try {
            coverSlots.withPermit { fetchBytes(url, MAX_COVER_BYTES) }
        } catch (e: IOException) {
            return null
        }
        val bitmap = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        covers.put(url, bitmap)
        return bitmap
    }

    /**
     * Downloads [book] and imports it exactly as a link typed on the add screen would be. Each of
     * its files is tried in turn while the one before is missing or cannot be read; a connection
     * that fails ends the attempt, since every other file would go the same way. The first
     * refusal is the one reported, as it concerns the file that was wanted.
     */
    suspend fun add(book: FreeBook, details: FreeBookDetails): Book {
        var refused: ImportException? = null
        for (file in details.files) {
            try {
                // An epub brings its own title, properly cased; plain text has the catalogue's.
                return BookRepository.addFromUrl(file.url, if (file.isEpub) "" else book.title)
            } catch (e: ImportException) {
                if (refused == null) refused = e
            }
        }
        throw refused ?: ImportException("Project Gutenberg has no .txt or .epub of this book")
    }

    /** An answer that is not the feed it should be, such as a network's sign-in page, is the library's failure. */
    private suspend fun <T> readFeed(read: () -> T): T = withContext(Dispatchers.Default) {
        try {
            read()
        } catch (e: SAXException) {
            throw CatalogueException("The free library sent something that could not be read")
        }
    }

    private suspend fun fetch(url: String, limit: Int): String = fetchBytes(url, limit).toString(Charsets.UTF_8)

    private suspend fun fetchBytes(url: String, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        // An answer nobody is waiting for any more is not sat out: closing the connection ends
        // the read at once, where a cancelled coroutine alone would block until it came.
        val hangUp = launch { try { awaitCancellation() } finally { conn.disconnect() } }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw CatalogueException("The free library answered $code")
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) throw CatalogueException("The free library sent more than expected")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            // A connection closed because nobody wants the answer any more is not a failure.
            ensureActive()
            throw e
        } finally {
            hangUp.cancel()
            conn.disconnect()
        }
    }

    /** The name the link import gives Gutenberg, which turns away a bare product token. */
    private const val USER_AGENT = "WatchReader/1.0 (Android; +https://yingwang.github.io/watchreader/)"

    /** A page of 25 results is some 60 KB, most of it the same small icon for every entry. */
    private const val MAX_FEED_BYTES = 2 * 1024 * 1024
    private const val MAX_COVER_BYTES = 2 * 1024 * 1024
}
