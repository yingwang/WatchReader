package com.watchreader.wear.data.repository

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ReadingProgress
import com.watchreader.wear.data.db.WearBookDao
import com.watchreader.wear.data.db.WearDatabase
import com.watchreader.wear.R
import com.watchreader.wear.data.model.WearBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WatchReader"

object WearBookRepository {
    private lateinit var dao: WearBookDao
    private lateinit var booksDir: File
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        dao = WearDatabase.get(context).wearBookDao()
        booksDir = File(context.filesDir, "books").also { it.mkdirs() }
    }

    fun getBooksDir(): File = booksDir

    /**
     * Puts the bundled guide in the library the first time the app runs, so a new watch has
     * something to open and something to read aloud before any book has been sent from the phone.
     * Deleting it is final: the flag stays set, so it does not come back.
     */
    suspend fun seedSampleIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences("watchreader_seed", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SAMPLE_SEEDED, false)) return@withContext
        runCatching {
            val text = appContext.assets.open(SAMPLE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val file = File(booksDir, "$SAMPLE_ID.txt")
            file.writeText(text, Charsets.UTF_8)
            dao.upsert(
                WearBook(
                    id = SAMPLE_ID,
                    title = appContext.getString(R.string.sample_title),
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                    addedEpochMs = System.currentTimeMillis(),
                    totalChars = text.length,
                )
            )
        }.onFailure { Log.w(TAG, "Could not lay down the sample book", it) }
        prefs.edit().putBoolean(KEY_SAMPLE_SEEDED, true).apply()
    }

    fun observeAll(): Flow<List<WearBook>> = dao.observeAll()

    suspend fun getById(id: String): WearBook? = dao.getById(id)

    suspend fun insert(book: WearBook) = dao.upsert(book)

    /** Removes a book locally; [tellPhone] sends the phone a note so it can show "not on watch". */
    suspend fun delete(id: String, tellPhone: Boolean) {
        val book = dao.getById(id) ?: return
        File(book.filePath).delete()
        dao.deleteById(id)
        if (tellPhone) sendToPhone(DataLayerPaths.BOOK_REMOVED_PATH, id.toByteArray(Charsets.UTF_8))
    }

    suspend fun updateProgress(id: String, offset: Int) {
        dao.updateProgress(id, offset, System.currentTimeMillis())
    }

    /** Best effort; the phone shows the percentage on the book's cover. */
    suspend fun sendProgressToPhone(book: WearBook, offset: Int) {
        val total = book.totalChars.takeIf { it > 0 } ?: return
        val progress = ReadingProgress(
            bookId = book.id,
            charOffset = offset,
            percentage = (offset.toFloat() / total).coerceIn(0f, 1f),
            lastReadEpochMs = System.currentTimeMillis(),
        )
        sendToPhone(DataLayerPaths.PROGRESS_PATH, progress.toJson().toByteArray(Charsets.UTF_8))
    }

    /** Books arrive as UTF-8 (the phone normalises them), so no charset guessing here. */
    suspend fun loadText(book: WearBook): String = withContext(Dispatchers.IO) {
        File(book.filePath).readText(Charsets.UTF_8)
    }

    private suspend fun sendToPhone(path: String, payload: ByteArray) {
        runCatching {
            val nodes = Wearable.getCapabilityClient(appContext)
                .getCapability(DataLayerPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await().nodes
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return
            Wearable.getMessageClient(appContext).sendMessage(node.id, path, payload).await()
        }.onFailure { Log.w(TAG, "Could not reach the phone for $path", it) }
    }

    private const val SAMPLE_ID = "sample"
    private const val SAMPLE_ASSET = "sample.txt"
    private const val KEY_SAMPLE_SEEDED = "sample_seeded"
}
