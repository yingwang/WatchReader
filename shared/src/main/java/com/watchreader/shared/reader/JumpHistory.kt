package com.watchreader.shared.reader

/** One return point for a sequence of jumps; ordinary page turns do not discard it. */
class JumpHistory {
    var returnOffset: Int? = null
        private set
    fun record(offset: Int) { if (returnOffset == null) returnOffset = offset }
    fun take(): Int? = returnOffset.also { returnOffset = null }
}
