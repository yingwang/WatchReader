package com.watchreader.shared.bookmark

import android.content.Context
import android.util.Log
import java.io.File
import com.watchreader.shared.search.TextExcerpt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BookmarkRepository private constructor(private val context: Context) {
    internal val store = BookmarkStore(BookmarkFileStorage(File(context.filesDir, "reader-bookmarks/bookmarks-v1.json")))
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    fun observe(bookId: String) = flow {
        withContext(Dispatchers.IO) { store.load() }
        emitAll(store.records.map { Bookmark.visible(it, bookId) }.distinctUntilChanged())
    }

    suspend fun add(bookId: String, offset: Int, text: String) = withContext(Dispatchers.IO) {
        store.put(bookId, offset, TextExcerpt.bookmark(text, offset))
        requestSync()
    }

    suspend fun remove(bookId: String, offset: Int) = withContext(Dispatchers.IO) {
        store.remove(bookId, offset)
        requestSync()
    }

    /** App startup, peer connection, and reader resume may all call this. Local saving needs no peer. */
    fun requestSync() {
        syncScope.launch {
            refreshLock.withLock {
                attempt { BookmarkSync.restore(context) }
                // Even if fetching the peer failed, try to persist local changes in Play services.
                attempt { BookmarkSync.flush(context) }
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("WatchReader", "Bookmark sync deferred; local records retained", error)
        }
    }

    companion object {
        @Volatile private var instance: BookmarkRepository? = null
        fun get(context: Context): BookmarkRepository = instance ?: synchronized(this) {
            instance ?: BookmarkRepository(context.applicationContext).also { instance = it }
        }
    }
}
