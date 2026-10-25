package com.watchreader.mobile

import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.transfer.TransferLimits
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
        val attempts = TransferRunner.Attempts.InMemory()
        val runner = TransferRunner(this, this, attempts, maxFailedSends = 3, gaveUp = "Gave up after 3 tries")
        override suspend fun book(id: String) = stored
        override suspend fun change(id: String, expected: SyncStatus, next: SyncStatus, message: String?): Boolean {
            changing(expected, next)
            val current = stored ?: return false
            if (current.syncStatus != expected) return false
            stored = current.copy(syncStatus = next, syncMessage = message)
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

    @Test fun sendsThatKeepFailingEndWithTheBookMarkedFailed() = runBlocking {
        val f = Fixture()
        f.sendResult = false
        assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one"))
        assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one"))
        assertEquals(SyncStatus.QUEUED, f.stored!!.syncStatus)
        assertEquals(TransferRunner.Outcome.FAILED, f.runner.run("one"))
        assertEquals(SyncStatus.FAILED, f.stored!!.syncStatus)
        assertEquals("Gave up after 3 tries", f.stored!!.syncMessage)
        assertEquals(3, f.sent)
    }

    @Test fun aSendWithNoReceiptCountsAsAFailedOne() = runBlocking {
        val f = Fixture()
        repeat(2) { assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one")) }
        assertEquals(TransferRunner.Outcome.FAILED, f.runner.run("one"))
        assertEquals(SyncStatus.FAILED, f.stored!!.syncStatus)
    }

    @Test fun aWatchOutOfReachIsNotAFailedSend() = runBlocking {
        val f = Fixture()
        f.online = false
        repeat(10) { assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one")) }
        f.online = true
        f.sendResult = false
        repeat(2) { assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one")) }
        assertEquals(SyncStatus.QUEUED, f.stored!!.syncStatus)
    }

    @Test fun aSendThatWorksStartsTheCountAgain() = runBlocking {
        val f = Fixture()
        f.sendResult = false
        repeat(2) { f.runner.run("one") }
        f.sendResult = true
        f.receipt = { f.status(SyncStatus.SENT) }
        assertEquals(TransferRunner.Outcome.DONE, f.runner.run("one"))
        // Sent again later, the book has all its tries.
        f.status(SyncStatus.QUEUED)
        f.receipt = {}
        f.sendResult = false
        repeat(2) { assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one")) }
        assertEquals(TransferRunner.Outcome.FAILED, f.runner.run("one"))
    }

    @Test fun aReceiptThatLandsAsTheLastTryEndsKeepsTheSuccess() = runBlocking {
        val f = Fixture()
        f.sendResult = false
        repeat(2) { f.runner.run("one") }
        // The receipt for the last try arrives just as it is given up.
        f.changing = { expected, next ->
            if (expected == SyncStatus.QUEUED && next == SyncStatus.FAILED) f.status(SyncStatus.SENT)
        }
        assertEquals(TransferRunner.Outcome.DONE, f.runner.run("one"))
        assertEquals(SyncStatus.SENT, f.stored!!.syncStatus)
    }

    @Test fun theTimeASendMayTakeGrowsWithTheBookWithinLimits() {
        assertEquals(TransferLimits.MIN_SEND_MS, TransferLimits.sendTimeoutMs(0))
        assertEquals(TransferLimits.MIN_SEND_MS, TransferLimits.sendTimeoutMs(500_000))
        val tenMegabytes = TransferLimits.sendTimeoutMs(10L * 1024 * 1024)
        assertTrue(tenMegabytes > TransferLimits.MIN_SEND_MS && tenMegabytes < TransferLimits.MAX_SEND_MS)
        // A 20 MB GB18030 book is about 30 MB as UTF-8.
        assertEquals(TransferLimits.MAX_SEND_MS, TransferLimits.sendTimeoutMs(30L * 1024 * 1024))
        assertEquals(TransferLimits.MAX_SEND_MS, TransferLimits.sendTimeoutMs(Long.MAX_VALUE / 2000))
        var previous = 0L
        for (mb in 0..40) {
            val t = TransferLimits.sendTimeoutMs(mb * 1024L * 1024)
            assertTrue("Less time for a longer book at $mb MB", t >= previous)
            previous = t
        }
    }

    @Test fun aSendAndItsReceiptFitInTheTimeWorkManagerAllowsAWorker() {
        // WorkManager stops a worker after ten minutes; the receipt may take ninety seconds more.
        assertTrue(TransferLimits.MAX_SEND_MS + 90_000 < 10 * 60_000)
        assertTrue(TransferLimits.MAX_FAILED_SENDS > 1)
    }

    @Test fun restartedProcessRecoversInterruptedTransfer() = runBlocking {
        val f = Fixture()
        f.status(SyncStatus.SENDING)
        assertEquals(TransferRunner.Outcome.RETRY, f.runner.run("one"))
        assertEquals(SyncStatus.QUEUED, f.stored!!.syncStatus)
        assertEquals(0, f.sent)
    }
}
