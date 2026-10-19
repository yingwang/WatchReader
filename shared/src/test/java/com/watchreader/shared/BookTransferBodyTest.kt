package com.watchreader.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BookTransferBodyTest {
    @Test fun completeUtf8BodyIsCopiedByteForByte() {
        val text = "书籍正文 🌿".toByteArray()
        val out = ByteArrayOutputStream()
        BookTransfer.copyBody(ByteArrayInputStream(text), out, text.size.toLong())
        assertArrayEquals(text, out.toByteArray())
    }

    @Test fun truncatedAndOversizedTransfersAreRejected() {
        for (expected in listOf(3L, 7L)) assertThrows(IOException::class.java) {
            BookTransfer.copyBody(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), ByteArrayOutputStream(), expected)
        }
    }

    @Test fun receiptRemainsCompatibleWithOlderWatches() {
        assertFalse(BookReceipt.fromJson("{\"bookId\":\"one\",\"ok\":false}").retryable)
        val retry = BookReceipt("one", false, "Interrupted", retryable = true)
        assertEquals(retry, BookReceipt.fromJson(retry.toJson()))
    }
}
