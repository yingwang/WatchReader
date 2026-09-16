package com.watchreader.wear.data.repository

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.watchreader.shared.DataLayerPaths
import com.watchreader.shared.ReadingProgress
import com.watchreader.shared.BookToc
import com.watchreader.shared.Chapter
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
     *
     * A later version of the app with a rewritten guide refreshes the copy on the watch, but only
     * while the guide is still there: once the user has swiped it away it stays gone.
     */
    suspend fun seedSampleIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences("watchreader_seed", Context.MODE_PRIVATE)
        val seeded = prefs.getInt(KEY_SAMPLE_VERSION, 0)
        if (seeded >= SAMPLE_VERSION) return@withContext
        val deletedByUser = seeded > 0 && dao.getById(SAMPLE_ID) == null
        if (!deletedByUser) {
            runCatching {
                val text = appContext.assets.open(SAMPLE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
                val file = File(booksDir, "$SAMPLE_ID.txt")
                file.writeText(text, Charsets.UTF_8)
                // A contents file left by an earlier edition sent from the phone would pair the
                // new text with the old offsets. This book is laid down rather than sent, so its
                // chapters are read out of the text here, the once, and kept like any other.
                storeContents(file, BookToc.toJson(BookToc.detect(text)))
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
        }
        prefs.edit().putInt(KEY_SAMPLE_VERSION, SAMPLE_VERSION).apply()
    }

    fun observeAll(): Flow<List<WearBook>> = dao.observeAll()

    suspend fun getById(id: String): WearBook? = dao.getById(id)

    suspend fun insert(book: WearBook) = dao.upsert(book)

    /** Removes a book locally; [tellPhone] sends the phone a note so it can show "not on watch". */
    suspend fun delete(id: String, tellPhone: Boolean) {
        val book = dao.getById(id) ?: return
        File(book.filePath).delete()
        File(book.filePath + ".toc.json").delete()
        dao.deleteById(id)
        if (tellPhone) sendToPhone(DataLayerPaths.BOOK_REMOVED_PATH, id.toByteArray(Charsets.UTF_8))
    }

    /** [atEpochMs] is when the reading happened; a save repeated later keeps that stamp. */
    suspend fun updateProgress(id: String, offset: Int, atEpochMs: Long = System.currentTimeMillis()) {
        dao.updateProgress(id, offset, atEpochMs)
    }

    /** Progress read on the phone. The later of the two readings wins (see the DAO's guard). */
    suspend fun applyProgressFromPhone(progress: ReadingProgress) {
        dao.updateProgress(progress.bookId, progress.charOffset.coerceAtLeast(0), progress.lastReadEpochMs)
    }

    /** Best effort; the phone shows the percentage on the book's cover. */
    suspend fun sendProgressToPhone(book: WearBook, offset: Int, atEpochMs: Long = System.currentTimeMillis()) {
        val total = book.totalChars.takeIf { it > 0 } ?: return
        val progress = ReadingProgress(
            bookId = book.id,
            charOffset = offset,
            percentage = (offset.toFloat() / total).coerceIn(0f, 1f),
            lastReadEpochMs = atEpochMs,
        )
        sendToPhone(DataLayerPaths.PROGRESS_PATH, progress.toJson().toByteArray(Charsets.UTF_8))
    }

    /** Books arrive as UTF-8 (the phone normalises them), so no charset guessing here. */
    suspend fun loadText(book: WearBook): String = withContext(Dispatchers.IO) {
        File(book.filePath).readText(Charsets.UTF_8)
    }

    /** Optional companion file avoids changing the database or the book's character offsets. */
    fun storeContents(bookFile: File, tocJson: String?) {
        val file = File(bookFile.path + ".toc.json")
        // Never reuse a previous edition's chapter offsets after a resend.
        if (file.exists() && !file.delete()) throw java.io.IOException("Could not replace book contents")
        if (tocJson != null) {
            runCatching { file.writeText(tocJson, Charsets.UTF_8) }
                .onFailure { file.delete(); Log.w(TAG, "Could not store optional contents", it) }
        }
    }

    /**
     * The contents that came with the book. Working them out here from the text alone is no
     * longer attempted: the phone has the file the book came from and can read the chapter list
     * out of it properly, while the watch has only the text, and a book that prints its own
     * contents defeated the guessing entirely by handing back the printed list, whose every
     * entry pointed at the opening pages. Books sent before the phone passed its contents along
     * have none here and show no chapters until they are sent again.
     */
    suspend fun loadChapters(book: WearBook, text: String): List<Chapter> = withContext(Dispatchers.IO) {
        val json = runCatching { File(book.filePath + ".toc.json").readText(Charsets.UTF_8) }.getOrNull()
        BookToc.declared(json, text)
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
    /** Bumped whenever the bundled guide is rewritten. */
    private const val SAMPLE_VERSION = 5
    private const val KEY_SAMPLE_VERSION = "sample_version"
}
