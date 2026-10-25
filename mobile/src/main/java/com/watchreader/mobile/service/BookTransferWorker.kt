package com.watchreader.mobile.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.transfer.TransferLimits
import com.watchreader.mobile.transfer.TransferRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Each book is durable work. Bluetooth transfers do not require an Internet connection. */
class BookTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_BOOK) ?: return Result.failure()
        if (!BookRepository.beginSending(id)) return Result.retry()
        try {
            val sender = BookSender(applicationContext)
            val runner = TransferRunner(object : TransferRunner.Store {
                override suspend fun book(id: String) = BookRepository.getById(id)
                override suspend fun change(id: String, expected: SyncStatus, next: SyncStatus, message: String?) =
                    BookRepository.changeSyncStatus(id, expected, next, message)
            }, object : TransferRunner.Link {
                override suspend fun destination() = (sender.findWatch() as? WatchLookup.Ready)?.nodeId
                override suspend fun send(book: Book, nodeId: String) = sender.sendBook(book, nodeId)
                override suspend fun awaitReceipt(id: String) {
                    withTimeoutOrNull(90_000) {
                        BookRepository.observe(id).first { it?.syncStatus != SyncStatus.SENDING }
                    }
                }
            }, attempts(applicationContext), gaveUp = applicationContext.getString(R.string.transfer_gave_up, TransferLimits.MAX_FAILED_SENDS))
            // One book at a time, receipt and all. The watch stores them one after another anyway,
            // and a book's time would otherwise run out while it waited behind the others.
            return when (sends.withLock { runner.run(id) }) {
                TransferRunner.Outcome.DONE -> Result.success()
                TransferRunner.Outcome.RETRY -> Result.retry()
                TransferRunner.Outcome.FAILED -> Result.failure()
            }
        } finally {
            BookRepository.endSending(id)
        }
    }

    companion object {
        private val requests = Mutex()
        private val sends = Mutex()
        const val KEY_BOOK = "bookId"
        private fun name(id: String) = "send-book-$id"
        fun schedule(context: Context, id: String, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<BookTransferWorker>()
                .setInputData(workDataOf(KEY_BOOK to id))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
        suspend fun enqueue(context: Context, book: Book) = requests.withLock {
            // Do not reset an active attempt or duplicate a pending request.
            val current = BookRepository.getById(book.id) ?: return@withLock
            if (current.syncStatus == SyncStatus.SENDING) return@withLock
            if (current.syncStatus != SyncStatus.QUEUED &&
                !BookRepository.changeSyncStatus(book.id, current.syncStatus, SyncStatus.QUEUED)) return@withLock
            // A send the reader asks for again starts with every try still to come.
            if (current.syncStatus != SyncStatus.QUEUED) attempts(context).clear(book.id)
            schedule(context, book.id)
        }
        suspend fun cancelWaiting(context: Context, id: String) = requests.withLock {
            if (BookRepository.changeSyncStatus(id, SyncStatus.QUEUED, SyncStatus.NOT_SENT)) {
                WorkManager.getInstance(context).cancelUniqueWork(name(id)).result.await()
                attempts(context).clear(id)
            }
        }
        suspend fun cancel(context: Context, id: String) = requests.withLock {
            WorkManager.getInstance(context).cancelUniqueWork(name(id)).result.await()
        }
        /** The failed sends of each book, kept where a worker run after process death finds them. */
        private fun attempts(context: Context): TransferRunner.Attempts = object : TransferRunner.Attempts {
            private val prefs = context.getSharedPreferences("transfer_attempts", Context.MODE_PRIVATE)
            override fun failed(id: String): Int = (prefs.getInt(id, 0) + 1).also { prefs.edit().putInt(id, it).apply() }
            override fun clear(id: String) = prefs.edit().remove(id).apply()
        }

        /** On reconnection, bring delayed work forward; never replace a running stream. */
        suspend fun resumeWaiting(context: Context, reconnect: Boolean = false) = requests.withLock {
            val manager = WorkManager.getInstance(context)
            for (id in BookRepository.queuedBookIds()) {
                val active = manager.getWorkInfosForUniqueWork(name(id)).await()
                val running = active.any { it.state == WorkInfo.State.RUNNING }
                schedule(context, id, replace = reconnect && !running)
            }
        }
    }
}
