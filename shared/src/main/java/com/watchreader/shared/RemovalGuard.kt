package com.watchreader.shared

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a book's deletion apart from the saves that would bring its reading back.
 *
 * The read-aloud service is told to stop when its book is deleted, but the stop is a command it
 * gets to later, and on its way out it saves its place and its listening time. Landing after the
 * delete had cleared them, that save wrote the book's time again and put its synced place back in
 * the data layer, where it came back with the book the next time it was sent. A reader screen
 * closed just before a delete saves on its way out in the same way.
 *
 * The repository deletes a book inside [removing], and every save that could create a book's
 * rows again goes through [ifKept], which waits for a deletion under way and writes nothing once
 * the book is gone. Only database writes and data items belong inside; a message to the other
 * device can wait for the answer and is sent after.
 */
class RemovalGuard(private val exists: suspend (bookId: String) -> Boolean) {
    private val lock = Mutex()

    /** Runs a deletion, with no guarded save for any book part-way through. */
    suspend fun <T> removing(block: suspend () -> T): T = lock.withLock { block() }

    /** Runs [write] only while [bookId] is still there; null, and nothing written, once it is gone. */
    suspend fun <T> ifKept(bookId: String, write: suspend () -> T): T? = lock.withLock {
        if (exists(bookId)) write() else null
    }
}
