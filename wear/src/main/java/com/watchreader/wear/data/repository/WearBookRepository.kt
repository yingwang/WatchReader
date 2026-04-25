package com.watchreader.wear.data.repository

import android.content.Context
import com.watchreader.wear.data.db.WearBookDao
import com.watchreader.wear.data.db.WearDatabase
import com.watchreader.wear.data.model.WearBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

object WearBookRepository {
    private lateinit var dao: WearBookDao
    private lateinit var booksDir: File

    fun init(context: Context) {
        dao = WearDatabase.get(context).wearBookDao()
        booksDir = File(context.filesDir, "books").also { it.mkdirs() }
    }

    fun getBooksDir(): File = booksDir

    fun observeAll(): Flow<List<WearBook>> = dao.observeAll()

    suspend fun getById(id: String): WearBook? = dao.getById(id)

    suspend fun insert(book: WearBook) = dao.upsert(book)

    suspend fun delete(id: String) {
        val book = dao.getById(id) ?: return
        File(book.filePath).delete()
        dao.deleteById(id)
    }

    suspend fun updateProgress(id: String, offset: Int) {
        dao.updateProgress(id, offset, System.currentTimeMillis())
    }

    suspend fun loadText(book: WearBook): String = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        val bytes = file.readBytes()
        // Try UTF-8 first, fall back to GBK for Chinese txt files
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            try {
                String(bytes, Charset.forName("GBK"))
            } catch (_: Exception) {
                text
            }
        } else {
            text
        }
    }
}
