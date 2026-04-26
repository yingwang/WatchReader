package com.watchreader.mobile.data.repository

import android.content.Context
import android.net.Uri
import com.watchreader.mobile.data.db.AppDatabase
import com.watchreader.mobile.data.db.BookDao
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.watchreader.mobile.util.EpubParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object BookRepository {
    private lateinit var dao: BookDao
    private lateinit var booksDir: File

    fun init(context: Context) {
        dao = AppDatabase.get(context).bookDao()
        booksDir = File(context.filesDir, "books").also { it.mkdirs() }
    }

    fun observeAll(): Flow<List<Book>> = dao.observeAll()

    suspend fun getById(id: String): Book? = dao.getById(id)

    suspend fun addFromUri(context: Context, uri: Uri, title: String): Book {
        val id = UUID.randomUUID().toString()
        val destFile = File(booksDir, "$id.txt")
        withContext(Dispatchers.IO) {
            val mimeType = context.contentResolver.getType(uri)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open file")
            if (mimeType == "application/epub+zip") {
                val text = EpubParser.parse(input)
                destFile.writeText(text)
            } else {
                input.use { inp ->
                    destFile.outputStream().use { output -> inp.copyTo(output) }
                }
            }
        }
        val book = Book(
            id = id,
            title = title,
            filePath = destFile.absolutePath,
            sizeBytes = destFile.length(),
            addedEpochMs = System.currentTimeMillis(),
        )
        dao.upsert(book)
        return book
    }

    suspend fun addFromUrl(url: String, title: String): Book = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val destFile = File(booksDir, "$id.txt")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.inputStream.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        val book = Book(
            id = id,
            title = title,
            filePath = destFile.absolutePath,
            sizeBytes = destFile.length(),
            addedEpochMs = System.currentTimeMillis(),
        )
        dao.upsert(book)
        book
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
        dao.updateProgress(id, progress)
    }
}
