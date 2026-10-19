package com.watchreader.mobile.transfer

import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Receipts are authoritative, even when they arrive at the same time as a timeout. */
class TransferRunner(private val store: Store, private val link: Link) {
    interface Store {
        suspend fun book(id: String): Book?
        suspend fun change(id: String, expected: SyncStatus, next: SyncStatus): Boolean
    }
    interface Link {
        suspend fun destination(): String?
        suspend fun send(book: Book, nodeId: String): Boolean
        suspend fun awaitReceipt(id: String)
    }
    enum class Outcome { DONE, RETRY, FAILED }

    suspend fun run(id: String): Outcome {
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
                        else -> Outcome.RETRY
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
}
