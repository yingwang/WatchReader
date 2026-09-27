package com.watchreader.mobile.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.watchreader.mobile.data.db.AppDatabase
import com.watchreader.mobile.data.db.BookDao
import com.watchreader.mobile.data.db.ReadingStatsDao
import com.watchreader.mobile.data.model.ReadingMilestone
import com.watchreader.mobile.data.model.ReadingTime
import com.watchreader.shared.stats.ReadingReport
import com.watchreader.shared.stats.ReadingSource
import com.watchreader.shared.stats.dayOf
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.service.BookSender
import com.watchreader.mobile.util.EpubParser
import com.watchreader.shared.BookToc
import com.watchreader.shared.Chapter
import com.watchreader.shared.ProgressDataSync
import com.watchreader.shared.ReadingProgress
import com.watchreader.shared.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownServiceException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/** Thrown for problems the user can act on; the message is already human-readable. */
class ImportException(message: String) : IOException(message)

object BookRepository {
    /** Largest file we are willing to import; a whole novel is a few megabytes. */
    const val MAX_BOOK_BYTES = 20L * 1024 * 1024

    private lateinit var dao: BookDao
    private lateinit var stats: ReadingStatsDao
    private lateinit var booksDir: File
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        dao = AppDatabase.get(context).bookDao()
        stats = AppDatabase.get(context).readingStatsDao()
        booksDir = File(context.filesDir, "books").also { it.mkdirs() }
    }

    fun observeAll(): Flow<List<Book>> = dao.observeAll()

    suspend fun getById(id: String): Book? = dao.getById(id)

    /** One book's row as it changes, readings from the watch included. */
    fun observe(id: String): Flow<Book?> = dao.observeById(id)

    /**
     * Imports a .txt or .epub picked or shared by the user. The text is decoded on the phone
     * (BOM, UTF-8, GB18030) and stored as UTF-8 so the watch never has to guess an encoding.
     * A blank [title] means "use the epub's own title, or the file name".
     */
    suspend fun addFromUri(context: Context, uri: Uri, title: String, fallbackTitle: String): Book =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: ""
            val imported = withinMemory {
                val bytes = resolver.openInputStream(uri)?.use { readLimited(it) }
                    ?: throw ImportException("Cannot open the selected file")
                importBytes(bytes, mimeType, title, fallbackTitle, declaredCharset = null)
            }
            // A screen left while the file was read has given up on it, and it stays out of the library.
            coroutineContext.ensureActive()
            store(imported)
        }

    /** Downloads a .txt or .epub from [url]; web pages are refused rather than saved as books. */
    suspend fun addFromUrl(url: String, title: String): Book = withContext(Dispatchers.IO) {
        val address = secureAddress(url)
        val parsed = runCatching { URL(address) }.getOrElse { throw ImportException("That is not a valid URL") }
        if (parsed.protocol != "https") {
            throw ImportException("Only http:// and https:// links can be downloaded")
        }
        val upgraded = address != url.trim()
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            // A bare product token is turned away by several book archives, Gutenberg included.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw ImportException("Server answered ${conn.responseCode}")
            }
            if (conn.contentLengthLong > MAX_BOOK_BYTES) {
                throw ImportException("File is larger than 20 MB")
            }
            val contentType = conn.contentType ?: ""
            val mime = contentType.substringBefore(';').trim().lowercase()
            val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)?.trim('"')
            val nameFromUrl = titleFromPath(parsed.path)
            val imported = withinMemory {
                val bytes = conn.inputStream.use { readLimited(it) }
                if (mime == "text/html" || (mime.isEmpty() && looksLikeHtml(bytes))) {
                    throw ImportException("That link is a web page, not a text or epub file")
                }
                importBytes(bytes, mime, title, nameFromUrl, charset)
            }
            // A screen left while the book downloaded has given up on it, and it stays out of the library.
            coroutineContext.ensureActive()
            store(imported)
        } catch (e: IOException) {
            // A download given up on ends quietly, whatever the connection said as it went.
            coroutineContext.ensureActive()
            throw when {
                e is ImportException -> e
                // Android refuses plain http outright, and a server that sends the download on
                // to such an address is refused with it.
                e is UnknownServiceException || e.message.orEmpty().contains("cleartext", ignoreCase = true) ->
                    ImportException("The server sent the download to a plain http:// address, which Android does not allow")
                // A link given as http:// or with no scheme was asked for over https, and a site
                // that never set that up turns the request away at the door.
                upgraded && (e is SSLException || e is ConnectException) ->
                    ImportException("That site does not offer a secure https:// download, and Android does not allow plain http://")
                else -> e
            }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val book = dao.getById(id) ?: return@withContext
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }
        dao.deleteById(id)
        stats.forget(id)
        runCatching { ProgressDataSync.forget(appContext, id) }
            .onFailure { Log.w("WatchReader", "Could not drop synced progress for $id", it) }
    }

    /** Time read on the phone, on the day it ends. */
    suspend fun recordReading(bookId: String, millis: Long, chars: Int, at: Long = System.currentTimeMillis()) {
        if (millis <= 0 && chars <= 0) return
        stats.add(bookId, dayOf(at), ReadingSource.PHONE, millis.coerceAtLeast(0), chars.coerceAtLeast(0))
    }

    suspend fun markOpened(bookId: String, at: Long = System.currentTimeMillis()) = stats.markOpened(bookId, at)

    suspend fun markFinished(bookId: String, at: Long = System.currentTimeMillis()) = stats.markFinished(bookId, at)

    /** The watch's reading time for one book, as it last reported it. */
    suspend fun applyWatchReport(report: ReadingReport) {
        // A report for a book the phone no longer has would bring back time nothing can show.
        if (dao.getById(report.bookId) == null) return
        stats.applyWatchReport(report)
    }

    fun observeReadingTime(): Flow<List<ReadingTime>> = stats.observeAll()

    fun observeReadingTime(bookId: String): Flow<List<ReadingTime>> = stats.observeFor(bookId)

    fun observeMilestone(bookId: String): Flow<ReadingMilestone?> = stats.observeMilestone(bookId)

    /** [message] is the watch's reason when a transfer failed; the library shows it under the cover. */
    suspend fun updateSyncStatus(id: String, status: SyncStatus, message: String? = null) {
        dao.updateSyncStatus(id, status, System.currentTimeMillis(), message?.takeIf { it.isNotBlank() })
    }

    /**
     * Books with a send under way in this process. The database says SENDING from the moment the
     * watch is found, but a second tap, or a second library screen opened by a share from another
     * app, reads that too late or cannot tell a live send from one whose waiter has gone.
     */
    private val sending: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** False when this book is already being sent, and the caller should leave it alone. */
    fun beginSending(id: String): Boolean = sending.add(id)

    fun endSending(id: String) {
        sending.remove(id)
    }

    /**
     * A book still marked SENDING when nothing is waiting for its receipt is stuck: the wait lives
     * in the screen that started the send and dies with it, while the status lives in the
     * database. Such books are marked failed so they can be sent again; a receipt that turns up
     * late still flips them to SENT. A send still running in this process is left alone. Returns
     * how many were recovered.
     */
    suspend fun recoverStaleTransfers(): Int {
        val stale = dao.idsWithStatus(SyncStatus.SENDING).filterNot { it in sending }
        for (id in stale) updateSyncStatus(id, SyncStatus.FAILED)
        return stale.size
    }

    /**
     * Android's backup carries the library to a new phone with each book's sync status, but not
     * the books on the watch, so every book would claim to be on a watch that may have none of
     * them. A restore is told apart by a marker kept where backup does not reach, next to a
     * preference, which does travel, saying the marker is in use: a restored phone has the
     * preference and no marker. There every book is marked not sent. An update from a version
     * that kept no marker has neither, and is left as it is.
     */
    suspend fun resetSyncAfterRestore(context: Context) {
        val marker = File(context.noBackupFilesDir, INSTALL_MARKER)
        val prefs = context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MARKER_IN_USE, false) && !marker.exists()) {
            dao.resetAllSyncStatus(SyncStatus.NOT_SENT, System.currentTimeMillis())
        }
        marker.createNewFile()
        prefs.edit().putBoolean(KEY_MARKER_IN_USE, true).apply()
    }

    /** Applies progress from either side; the older of two readings loses (see the DAO's guard). */
    suspend fun applyProgress(progress: ReadingProgress) {
        dao.updateProgress(
            progress.bookId,
            progress.charOffset.coerceAtLeast(0),
            progress.percentage.coerceIn(0f, 1f),
            progress.lastReadEpochMs,
        )
    }

    /**
     * Records where the reader on the phone got to and, when [toWatch] is set, tells the watch.
     * [atEpochMs] is when the page was turned; a save repeated later keeps that stamp, so both
     * sides' guards still let a more recent reading from the other device win. Telling the watch
     * costs a capability lookup and wakes its listener, so the reader does it every few turns
     * and on the way out rather than on every page.
     */
    suspend fun saveProgress(
        context: Context,
        book: Book,
        offset: Int,
        atEpochMs: Long = System.currentTimeMillis(),
        toWatch: Boolean = true,
    ) {
        val total = book.totalChars.takeIf { it > 0 } ?: return
        val progress = ReadingProgress(
            bookId = book.id,
            charOffset = offset.coerceIn(0, total),
            percentage = (offset.toFloat() / total).coerceIn(0f, 1f),
            lastReadEpochMs = atEpochMs,
        )
        applyProgress(progress)
        if (toWatch) BookSender(context).sendProgress(progress)
    }

    /**
     * Puts the bundled guide in an empty library on first run. A reader who deletes it is not
     * given it back; a rewritten guide reaches everyone who still has the old one.
     */
    suspend fun seedSampleIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("watchreader", Context.MODE_PRIVATE)
        val seeded = prefs.getInt(KEY_SAMPLE_VERSION, 0)
        if (seeded >= SAMPLE_VERSION) return@withContext
        if (seeded > 0 && dao.getById(SAMPLE_ID) == null) {
            prefs.edit().putInt(KEY_SAMPLE_VERSION, SAMPLE_VERSION).apply()
            return@withContext
        }
        val text = runCatching {
            context.assets.open(SAMPLE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { return@withContext }
        val file = File(booksDir, "$SAMPLE_ID.txt")
        file.writeText(text, Charsets.UTF_8)
        val existing = dao.getById(SAMPLE_ID)
        dao.upsert(
            Book(
                id = SAMPLE_ID,
                title = text.lineSequence().first().trim().ifBlank { "Start here" },
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                addedEpochMs = existing?.addedEpochMs ?: System.currentTimeMillis(),
                syncStatus = existing?.syncStatus ?: SyncStatus.NOT_SENT,
                totalChars = text.length,
                tocJson = BookToc.toJson(BookToc.detect(text)),
            ),
        )
        prefs.edit().putInt(KEY_SAMPLE_VERSION, SAMPLE_VERSION).apply()
    }

    suspend fun loadText(book: Book): String = withContext(Dispatchers.IO) {
        File(book.filePath).readText(Charsets.UTF_8)
    }

    // ---- internals ----

    private class Imported(val title: String, val text: String, val cover: ByteArray?, val chapters: List<Chapter>)

    private fun importBytes(
        bytes: ByteArray,
        mimeType: String,
        title: String,
        fallbackTitle: String,
        declaredCharset: String?,
    ): Imported {
        if (bytes.isEmpty()) throw ImportException("The file is empty")
        val isEpub = mimeType == "application/epub+zip" || EpubParser.looksLikeEpub(bytes)
        return if (isEpub) {
            // The parser's refusals, a copy-protected book among them, are already worded for the reader.
            val epub = try {
                EpubParser.parse(bytes.inputStream())
            } catch (e: IllegalArgumentException) {
                throw ImportException(e.message ?: "This epub could not be read")
            }
            if (epub.text.isBlank()) throw ImportException("No readable text found in this epub")
            Imported(title.ifBlank { epub.title.ifBlank { fallbackTitle } }, epub.text, epub.cover, epub.chapters)
        } else {
            // The picker offers files by their generic type as well, since some providers label
            // every .txt and .epub that way, and a Kindle book or a PDF comes in by the same door.
            if (TextNormalizer.looksBinary(bytes)) {
                throw ImportException(
                    "This file is not a .txt or .epub book. Kindle books (.mobi, .azw3), PDFs and " +
                        "other formats need converting to epub first.",
                )
            }
            val decoded = TextNormalizer.decode(bytes, declaredCharset)
            if (decoded.text.isBlank()) throw ImportException("No readable text found in this file")
            Imported(title.ifBlank { fallbackTitle }, decoded.text, cover = null, chapters = BookToc.detect(decoded.text))
        }
    }

    private suspend fun store(imported: Imported): Book {
        val id = UUID.randomUUID().toString()
        val destFile = File(booksDir, "$id.txt")
        val coverFile = imported.cover?.let { File(booksDir, "$id.cover") }
        // The file and its row go down together: a screen left mid-import must not leave a file
        // on disk that no row points at.
        return withContext(Dispatchers.IO + NonCancellable) {
            destFile.writeText(imported.text, Charsets.UTF_8)
            if (coverFile != null) coverFile.writeBytes(imported.cover)
            val book = Book(
                id = id,
                title = imported.title.trim().ifBlank { "Untitled" },
                filePath = destFile.absolutePath,
                sizeBytes = destFile.length(),
                addedEpochMs = System.currentTimeMillis(),
                totalChars = imported.text.length,
                coverPath = coverFile?.absolutePath,
                // Written even when empty: a book known to have no chapters is not scanned for
                // them again on every opening, here or on the watch.
                tocJson = BookToc.toJson(imported.chapters),
            )
            dao.upsert(book)
            book
        }
    }

    /**
     * An import turns a book of a few megabytes into several copies of its text at once, and a
     * phone with little memory to spare can run out partway. That is a file too large for this
     * phone, which the reader is told, rather than a crash that takes the app down with it.
     */
    private inline fun <T> withinMemory(block: () -> T): T = try {
        block()
    } catch (e: OutOfMemoryError) {
        throw ImportException("This file is too large to import on this phone")
    }

    /**
     * [url] as an https address. Android refuses plain http, and a link typed without a scheme,
     * as a site's address usually is, names no protocol at all; both are asked for over https,
     * which almost every site that serves books now answers.
     */
    internal fun secureAddress(url: String): String {
        val trimmed = url.trim()
        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)
        return when {
            scheme == null -> "https://" + trimmed.removePrefix("//")
            scheme.equals("http", ignoreCase = true) -> "https" + trimmed.substring(scheme.length)
            else -> trimmed
        }
    }

    /** A scheme and the slashes after it; in "example.com:8080/book.txt" the colon comes before a port. */
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

    /** The name a link gives its file, decoded, as the title of a book that brings none. */
    internal fun titleFromPath(path: String): String {
        val name = path.substringAfterLast('/')
        // URLDecoder is for form encoding, where + is a space; in a path it is a plus sign.
        val decoded = runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
        return decoded.substringBeforeLast('.').trim().ifBlank { "Untitled" }
    }

    private suspend fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BOOK_BYTES) throw ImportException("File is larger than 20 MB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).lowercase()
        return head.contains("<html") || head.contains("<!doctype html")
    }

    private const val USER_AGENT = "WatchReader/1.0 (Android; +https://yingwang.github.io/watchreader/)"

    private const val SAMPLE_ID = "sample"
    private const val INSTALL_MARKER = "install_marker"
    private const val INSTALL_PREFS = "install"
    private const val KEY_MARKER_IN_USE = "marker_in_use"
    private const val SAMPLE_ASSET = "sample.txt"
    /** Bumped whenever the bundled guide is rewritten. */
    private const val SAMPLE_VERSION = 3
    private const val KEY_SAMPLE_VERSION = "sample_version"
}
