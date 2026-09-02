package com.watchreader.mobile.data.repository

import android.content.Context
import android.net.Uri
import com.watchreader.mobile.data.db.AppDatabase
import com.watchreader.mobile.data.db.BookDao
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.service.BookSender
import com.watchreader.mobile.util.EpubParser
import com.watchreader.shared.BookToc
import com.watchreader.shared.Chapter
import com.watchreader.shared.ReadingProgress
import com.watchreader.shared.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownServiceException
import java.util.UUID

/** Thrown for problems the user can act on; the message is already human-readable. */
class ImportException(message: String) : IOException(message)

object BookRepository {
    /** Largest file we are willing to import; a whole novel is a few megabytes. */
    const val MAX_BOOK_BYTES = 20L * 1024 * 1024

    private lateinit var dao: BookDao
    private lateinit var booksDir: File

    fun init(context: Context) {
        dao = AppDatabase.get(context).bookDao()
        booksDir = File(context.filesDir, "books").also { it.mkdirs() }
    }

    fun observeAll(): Flow<List<Book>> = dao.observeAll()

    suspend fun getById(id: String): Book? = dao.getById(id)

    /**
     * Imports a .txt or .epub picked or shared by the user. The text is decoded on the phone
     * (BOM, UTF-8, GB18030) and stored as UTF-8 so the watch never has to guess an encoding.
     * A blank [title] means "use the epub's own title, or the file name".
     */
    suspend fun addFromUri(context: Context, uri: Uri, title: String, fallbackTitle: String): Book =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: ""
            val bytes = resolver.openInputStream(uri)?.use { readLimited(it) }
                ?: throw ImportException("Cannot open the selected file")
            val imported = importBytes(bytes, mimeType, title, fallbackTitle, declaredCharset = null)
            store(imported)
        }

    /** Downloads a .txt or .epub from [url]; web pages are refused rather than saved as books. */
    suspend fun addFromUrl(url: String, title: String): Book = withContext(Dispatchers.IO) {
        val parsed = runCatching { URL(url.trim()) }.getOrElse { throw ImportException("That is not a valid URL") }
        val conn = try {
            (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                // A bare product token is turned away by several book archives, Gutenberg included.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
        } catch (e: UnknownServiceException) {
            throw ImportException("Plain http:// links are blocked by Android; use https://")
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
            val nameFromUrl = parsed.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Untitled" }
            val bytes = conn.inputStream.use { readLimited(it) }
            if (mime == "text/html" || (mime.isEmpty() && looksLikeHtml(bytes))) {
                throw ImportException("That link is a web page, not a text or epub file")
            }
            val imported = importBytes(bytes, mime, title, nameFromUrl, charset)
            store(imported)
        } catch (e: UnknownServiceException) {
            throw ImportException("Plain http:// links are blocked by Android; use https://")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun delete(id: String) {
        val book = dao.getById(id) ?: return
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }
        dao.deleteById(id)
    }

    suspend fun updateSyncStatus(id: String, status: SyncStatus) {
        dao.updateSyncStatus(id, status, System.currentTimeMillis())
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

    /** Records where the reader on the phone got to, and tells the watch about it. */
    suspend fun saveProgress(context: Context, book: Book, offset: Int) {
        val total = book.totalChars.takeIf { it > 0 } ?: return
        val progress = ReadingProgress(
            bookId = book.id,
            charOffset = offset.coerceIn(0, total),
            percentage = (offset.toFloat() / total).coerceIn(0f, 1f),
            lastReadEpochMs = System.currentTimeMillis(),
        )
        applyProgress(progress)
        BookSender(context).sendProgress(progress)
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
                tocJson = BookToc.detect(text).takeIf { it.isNotEmpty() }?.let { BookToc.toJson(it) },
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
            val epub = EpubParser.parse(bytes.inputStream())
            if (epub.text.isBlank()) throw ImportException("No readable text found in this epub")
            Imported(title.ifBlank { epub.title.ifBlank { fallbackTitle } }, epub.text, epub.cover, epub.chapters)
        } else {
            val decoded = TextNormalizer.decode(bytes, declaredCharset)
            if (decoded.text.isBlank()) throw ImportException("No readable text found in this file")
            Imported(title.ifBlank { fallbackTitle }, decoded.text, cover = null, chapters = BookToc.detect(decoded.text))
        }
    }

    private suspend fun store(imported: Imported): Book {
        val id = UUID.randomUUID().toString()
        val destFile = File(booksDir, "$id.txt")
        val coverFile = imported.cover?.let { File(booksDir, "$id.cover") }
        withContext(Dispatchers.IO) {
            destFile.writeText(imported.text, Charsets.UTF_8)
            if (coverFile != null) coverFile.writeBytes(imported.cover)
        }
        val book = Book(
            id = id,
            title = imported.title.trim().ifBlank { "Untitled" },
            filePath = destFile.absolutePath,
            sizeBytes = destFile.length(),
            addedEpochMs = System.currentTimeMillis(),
            totalChars = imported.text.length,
            coverPath = coverFile?.absolutePath,
            tocJson = imported.chapters.takeIf { it.isNotEmpty() }?.let { BookToc.toJson(it) },
        )
        dao.upsert(book)
        return book
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
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
    private const val SAMPLE_ASSET = "sample.txt"
    /** Bumped whenever the bundled guide is rewritten. */
    private const val SAMPLE_VERSION = 2
    private const val KEY_SAMPLE_VERSION = "sample_version"
}
