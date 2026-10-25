package com.watchreader.shared

/**
 * The stamps a reader screen gives the places it saves, each later than the one before. Several
 * saves can come within one millisecond (one turn of the watch's crown can turn three pages), and
 * each is written on a thread of its own; the database keeps a reading only when it is at least as
 * recent as the one it has, so among equal stamps whichever landed last won, and the page left in
 * the database could be the second of the three rather than the last.
 */
class SaveStamps {
    private var last = 0L

    /** [now], or one past the last stamp when that is not earlier. */
    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): Long = maxOf(now, last + 1).also { last = it }
}
