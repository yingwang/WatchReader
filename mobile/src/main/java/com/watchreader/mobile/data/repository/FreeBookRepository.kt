package com.watchreader.mobile.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.FreeBookPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The free library answered, but not with a page of books. */
class CatalogueException(message: String) : IOException(message)

/**
 * Project Gutenberg's public-domain books, found through Gutendex and added through the same
 * link import as any other book. Pages and covers are kept in memory for the life of the
 * process, so going back to a list already seen costs nothing; nothing is kept on disk.
 */
object FreeBookRepository {
    private val pages = LruCache<String, FreeBookPage>(32)

    /** Gutenberg's covers are 200 pixels wide, a quarter of a megabyte decoded: some seventy of them. */
    private val covers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** A list scrolled quickly asks for many covers at once; Gutenberg is asked for a few at a time. */
    private val coverSlots = Semaphore(4)

    /** The most downloaded books, or those whose title or author matches [search]. */
    suspend fun firstPage(search: String, language: String?): FreeBookPage = page(Gutendex.pageUrl(search, language))

    /** The page at [url], a page's next link. */
    suspend fun page(url: String): FreeBookPage {
        pages.get(url)?.let { return it }
        val json = fetch(url, CATALOGUE_TIMEOUT_MS, MAX_PAGE_BYTES).toString(Charsets.UTF_8)
        val page = try {
            withContext(Dispatchers.Default) { Gutendex.parsePage(json) }
        } catch (e: JSONException) {
            throw CatalogueException("The free library sent something that is not a list of books")
        }
        pages.put(url, page)
        return page
    }

    fun cachedCover(url: String): Bitmap? = covers.get(url)

    /** The cover at [url], scaled down to about [targetWidth] pixels; null when there is none to be had. */
    suspend fun cover(url: String, targetWidth: Int): Bitmap? {
        covers.get(url)?.let { return it }
        val bytes = try {
            coverSlots.withPermit { fetch(url, COVER_TIMEOUT_MS, MAX_COVER_BYTES) }
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
    suspend fun add(book: FreeBook): Book {
        var refused: ImportException? = null
        for (file in book.files) {
            try {
                // An epub brings its own title, properly cased; plain text has the catalogue's.
                return BookRepository.addFromUrl(file.url, if (file.isEpub) "" else book.title)
            } catch (e: ImportException) {
                if (refused == null) refused = e
            }
        }
        throw refused ?: ImportException("Project Gutenberg has no .txt or .epub of this book")
    }

    private suspend fun fetch(url: String, readTimeoutMs: Int, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        // A slow answer nobody is waiting for any more is not sat out: closing the connection
        // ends the read at once, where a cancelled coroutine alone would block until it came.
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

    /**
     * Gutendex has been seen taking from one minute to nearly three over a query it has not
     * answered lately. It finishes one given up on all the same and keeps the answer, so trying
     * again a little later usually brings it at once.
     */
    private const val CATALOGUE_TIMEOUT_MS = 180_000
    private const val COVER_TIMEOUT_MS = 30_000
    private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_COVER_BYTES = 2 * 1024 * 1024
}
