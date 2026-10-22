package com.watchreader.mobile.highlight

import java.io.File

/**
 * One [HighlightStore] per book for the whole process, so two screens open on the same book (a
 * reader left in the back stack, say) share one copy of the file rather than each overwriting
 * what the other saved. Highlights stay on the phone; nothing here reaches the watch.
 */
object HighlightRepository {
    private val stores = HashMap<String, HighlightStore>()

    fun store(filesDir: File, bookId: String): HighlightStore = synchronized(stores) {
        stores.getOrPut(bookId) { HighlightStore(HighlightFileStorage(HighlightFiles.fileFor(filesDir, bookId), bookId)) }
    }

    /** Deletes a book's highlights, for a book deleted from the library. */
    suspend fun forget(filesDir: File, bookId: String) {
        val open = synchronized(stores) { stores.remove(bookId) }
        if (open != null) open.forget() else HighlightFiles.delete(HighlightFiles.fileFor(filesDir, bookId))
    }
}
