package com.watchreader.mobile.transfer

import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Receipts are authoritative, even when they arrive at the same time as a timeout. A book whose
 * sends keep reaching the watch and failing is marked failed after [maxFailedSends] of them, with
 * [gaveUp] as the reason the library shows; a watch out of reach counts for nothing.
 */
class TransferRunner(
    private val store: Store,
    private val link: Link,
    private val attempts: Attempts = Attempts.InMemory(),
    private val maxFailedSends: Int = TransferLimits.MAX_FAILED_SENDS,
    private val gaveUp: String? = null,
) {
    interface Store {
        suspend fun book(id: String): Book?
        suspend fun change(id: String, expected: SyncStatus, next: SyncStatus, message: String? = null): Boolean
    }
    interface Link {
        suspend fun destination(): String?
        suspend fun send(book: Book, nodeId: String): Boolean
        suspend fun awaitReceipt(id: String)
    }

    /** The failed sends of each book so far, kept from one run of its work to the next. */
    interface Attempts {
        /** Counts one more failed send of [id], and says how many there have been. */
        fun failed(id: String): Int
        fun clear(id: String)

        class InMemory : Attempts {
            private val counts = HashMap<String, Int>()
            override fun failed(id: String): Int = (counts[id] ?: 0).plus(1).also { counts[id] = it }
            override fun clear(id: String) { counts.remove(id) }
        }
    }

    enum class Outcome { DONE, RETRY, FAILED }

    /** One run of a book's work. Once the book is sent, gone or failed, its count starts again. */
    suspend fun run(id: String): Outcome = attempt(id).also { if (it != Outcome.RETRY) attempts.clear(id) }

    private suspend fun attempt(id: String): Outcome {
        val book = store.book(id) ?: return Outcome.DONE
        if (book.syncStatus == SyncStatus.SENDING) {
            // A worker restarted after process death has no live stream to wait for.
            store.change(id, SyncStatus.SENDING, SyncStatus.QUEUED)
            return Outcome.RETRY
        }
        if (book.syncStatus != SyncStatus.QUEUED) return Outcome.DONE
        var claimed = false
        try {
            val node = link.destination() ?: return Outcome.RETRY
            claimed = store.change(id, SyncStatus.QUEUED, SyncStatus.SENDING)
            if (!claimed) return Outcome.DONE
            if (link.send(book, node)) link.awaitReceipt(id)
            return when (store.book(id)?.syncStatus) {
                SyncStatus.SENT, SyncStatus.NOT_SENT, null -> Outcome.DONE
                SyncStatus.FAILED -> Outcome.FAILED
                else -> {
                    store.change(id, SyncStatus.SENDING, SyncStatus.QUEUED)
                    // A receipt may have landed while the conditional write was waiting.
                    when (store.book(id)?.syncStatus) {
                        SyncStatus.SENT, SyncStatus.NOT_SENT, null -> Outcome.DONE
                        SyncStatus.FAILED -> Outcome.FAILED
                        else -> if (attempts.failed(id) >= maxFailedSends) giveUp(id) else Outcome.RETRY
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Outcome.RETRY
        } finally {
            if (claimed) withContext(NonCancellable) {
                store.change(id, SyncStatus.SENDING, SyncStatus.QUEUED)
            }
        }
    }

    /** The book leaves the queue as failed, with the reason; the library offers to try again. */
    private suspend fun giveUp(id: String): Outcome {
        if (store.change(id, SyncStatus.QUEUED, SyncStatus.FAILED, gaveUp)) return Outcome.FAILED
        return when (store.book(id)?.syncStatus) {
            SyncStatus.SENT, SyncStatus.NOT_SENT, null -> Outcome.DONE
            SyncStatus.FAILED -> Outcome.FAILED
            else -> Outcome.RETRY
        }
    }
}
