package com.watchreader.mobile

import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.transfer.TransferRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransferRunnerTest {
    private class Fixture : TransferRunner.Store, TransferRunner.Link {
        var stored: Book? = Book("one", "Book", "book.txt", 10, 1, SyncStatus.QUEUED)
        var online = true
        var sent = 0
        var sendResult = true
        var finding: () -> Unit = {}
        var sending: () -> Unit = {}
        var receipt: () -> Unit = {}
        var changing: (SyncStatus, SyncStatus) -> Unit = { _, _ -> }
        val runner = TransferRunner(this, this)
        override suspend fun book(id: String) = stored
        override suspend fun change(id: String, expected: SyncStatus, next: SyncStatus): Boolean {
            changing(expected, next)
            val current = stored ?: return false
            if (current.syncStatus != expected) return false
            stored = current.copy(syncStatus = next)
            return true
        }
        override suspend fun destination(): String? { finding(); return if (online) "watch" else null }
        override suspend fun send(book: Book, nodeId: String): Boolean { sent++; sending(); return sendResult }
        override suspend fun awaitReceipt(id: String) { receipt() }
        fun status(status: SyncStatus) { stored = stored?.copy(syncStatus = status) }
    }

    @Test fun offlineWorkSurvivesAndCompletesAfterReconnection() = runBlocking {
        val f = Fixture()
        f.online = false
        assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one"))
        assertEquals(SyncStatus.QUEUED, f.stored!!.syncStatus)
        assertEquals(0, f.sent)
        f.online = true
        f.receipt = { f.status(SyncStatus.SENT) }
        assertEquals(TransferRunner.Outcome.DONE, f.runner.run("one"))
        assertEquals(SyncStatus.SENT, f.stored!!.syncStatus)
    }

    @Test fun missingReceiptRequeuesInsteadOfClaimingSuccess() = runBlocking {
        val f = Fixture()
        assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one"))
        assertEquals(SyncStatus.QUEUED, f.stored!!.syncStatus)
    }

    @Test fun lateReceiptWinsTimeoutAndFinallyCleanup() = runBlocking {
        val f = Fixture()
        f.changing = { expected, next ->
            if (expected == SyncStatus.SENDING && next == SyncStatus.QUEUED) f.status(SyncStatus.SENT)
        }
        assertEquals(TransferRunner.Outcome.DONE, f.runner.run("one"))
        assertEquals(SyncStatus.SENT, f.stored!!.syncStatus)
    }

    @Test fun watchRejectionIsNotRetriedForever() = runBlocking {
        val f = Fixture()
        f.receipt = { f.status(SyncStatus.FAILED) }
        assertEquals(TransferRunner.Outcome.FAILED, f.runner.run("one"))
        assertEquals(SyncStatus.FAILED, f.stored!!.syncStatus)
    }

    @Test fun transportFailureRequeuesButDoesNotUndoEarlySuccess() = runBlocking {
        val f = Fixture()
        f.sendResult = false
        assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one"))
        f.sending = { f.status(SyncStatus.SENT) }
        assertEquals(TransferRunner.Outcome.DONE, f.runner.run("one"))
        assertEquals(SyncStatus.SENT, f.stored!!.syncStatus)
    }

    @Test fun stoppedWorkerPreservesQueuedWorkAndCancellation() = runBlocking {
        val f = Fixture()
        f.sending = { throw CancellationException("WorkManager stopped") }
        try { f.runner.run("one"); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(SyncStatus.QUEUED, f.stored!!.syncStatus)
    }

    @Test fun deletionOrCancellationDuringDiscoveryDoesNotSend() = runBlocking {
        for (deleted in listOf(false, true)) {
            val f = Fixture()
            f.finding = { if (deleted) f.stored = null else f.status(SyncStatus.NOT_SENT) }
            assertEquals(TransferRunner.Outcome.DONE, f.runner.run("one"))
            assertEquals(0, f.sent)
        }
    }

    @Test fun restartedProcessRecoversInterruptedTransfer() = runBlocking {
        val f = Fixture()
        f.status(SyncStatus.SENDING)
        assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one"))
        assertEquals(SyncStatus.QUEUED, f.stored!!.syncStatus)
        assertEquals(0, f.sent)
    }
}
