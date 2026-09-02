package com.watchreader.mobile.data.repository

import android.content.Context
import android.net.Uri
import com.watchreader.mobile.data.db.AppDatabase
import com.watchreader.mobile.data.db.BookDao
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.util.EpubParser
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
                setRequestProperty("User-Agent", "WatchReader")
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
        dao.deleteById(id)
    }

    suspend fun updateSyncStatus(id: String, status: SyncStatus) {
        dao.updateSyncStatus(id, status, System.currentTimeMillis())
    }

    suspend fun updateProgress(id: String, progress: Float) {
        dao.updateProgress(id, progress.coerceIn(0f, 1f))
    }

    // ---- internals ----

    private class Imported(val title: String, val text: String)

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
            Imported(title.ifBlank { epub.title.ifBlank { fallbackTitle } }, epub.text)
        } else {
            val decoded = TextNormalizer.decode(bytes, declaredCharset)
            if (decoded.text.isBlank()) throw ImportException("No readable text found in this file")
            Imported(title.ifBlank { fallbackTitle }, decoded.text)
        }
    }

    private suspend fun store(imported: Imported): Book {
        val id = UUID.randomUUID().toString()
        val destFile = File(booksDir, "$id.txt")
        withContext(Dispatchers.IO) { destFile.writeText(imported.text, Charsets.UTF_8) }
        val book = Book(
            id = id,
            title = imported.title.trim().ifBlank { "Untitled" },
            filePath = destFile.absolutePath,
            sizeBytes = destFile.length(),
            addedEpochMs = System.currentTimeMillis(),
            totalChars = imported.text.length,
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
}
