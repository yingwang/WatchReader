package com.watchreader.shared.stats

/**
 * Counts the time spent reading one book on one screen.
 *
 * A page is being read from the moment it appears until the next one does, but a page left
 * open is not read for ever: the watch keeps its screen on, so a book can sit open on the
 * wrist through a meeting. Each page counts for at most [capMs]. The clock stops while the
 * screen is off or elsewhere and while the book is being read aloud, which counts as listening.
 */
class ReadingClock(private val capMs: Long = CAP_MS) {
    private var pageShownAt: Long? = null

    val running: Boolean get() = pageShownAt != null

    /** The screen is up and a page is showing. */
    fun start(now: Long) {
        if (pageShownAt == null) pageShownAt = now
    }

    /** A new page appeared; returns the reading time the page before it earned. */
    fun turn(now: Long): Long {
        val shown = pageShownAt ?: return 0
        pageShownAt = now
        return credit(shown, now)
    }

    /** The screen went away or the voice took over; returns the time the open page earned. */
    fun stop(now: Long): Long {
        val shown = pageShownAt ?: return 0
        pageShownAt = null
        return credit(shown, now)
    }

    private fun credit(from: Long, to: Long): Long = (to - from).coerceIn(0, capMs)

    companion object {
        /** The most a single page can count for. */
        const val CAP_MS = 2 * 60 * 1000L
    }
}
