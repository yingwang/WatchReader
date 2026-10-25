package com.watchreader.mobile.transfer

/**
 * How long one send of a book may take, and how many failed sends a book gets before it stops
 * going back to the queue.
 *
 * Every send used to have three minutes whatever its size. A twenty-megabyte GB18030 book is
 * thirty megabytes once it is UTF-8, more than a slow Bluetooth link carries in that time; the
 * send timed out, the watch reported an incomplete book, the book went back to the queue and the
 * next try ended the same way, for ever.
 */
object TransferLimits {
    /** Finding the watch, opening the channel and the watch taking it up, whatever the size. */
    private const val SETUP_MS = 120_000L

    /** The slowest link a send is expected to manage, in bytes a second; a guess, not a measurement. */
    private const val SLOWEST_BYTES_PER_SECOND = 64L * 1024

    /** No send has less than the three minutes every send used to have... */
    const val MIN_SEND_MS = 180_000L

    /**
     * ...or more than eight. WorkManager stops a worker after ten minutes, and the receipt may
     * take another ninety seconds once the bytes are across.
     */
    const val MAX_SEND_MS = 480_000L

    /** Sends that reach the watch and fail, or bring no receipt, before the book is marked failed. */
    const val MAX_FAILED_SENDS = 5

    /** The time allowed for sending [bytes], header included, to the watch. */
    fun sendTimeoutMs(bytes: Long): Long =
        (SETUP_MS + bytes.coerceAtLeast(0) * 1000 / SLOWEST_BYTES_PER_SECOND).coerceIn(MIN_SEND_MS, MAX_SEND_MS)
}
