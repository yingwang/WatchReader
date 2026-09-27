package com.watchreader.shared.reader

/**
 * Where the last jump left from, so the reader can go back to it.
 *
 * Jumps made one after another with no reading between them are one search, and going back
 * returns to where the search began. Once the reader turns a page the search is over: the next
 * jump leaves from what they were reading, and that is where going back returns to.
 */
class JumpHistory {
    var returnOffset: Int? = null
        private set
    private var readSinceJump = false

    fun record(offset: Int) {
        if (returnOffset == null || readSinceJump) returnOffset = offset
        readSinceJump = false
    }

    /** The reader turned a page, by hand or by the voice reading aloud. */
    fun turned() { readSinceJump = true }

    fun take(): Int? = returnOffset.also {
        returnOffset = null
        readSinceJump = false
    }
}
