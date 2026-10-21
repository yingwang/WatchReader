package com.watchreader.mobile.ui.viewmodel

/**
 * Where the reader was before jumping elsewhere in the book (the contents, the slider, a search
 * result, a bookmark), so a look-up can end with one tap back. It is kept until the reader goes
 * back, turns pages until they reach it again, or lets it go.
 */
class ReturnPoint {
    var offset: Int? = null
        private set

    /** Keeps [from] unless a place is kept already: after several look-ups, back is still where the reading was. */
    fun jumpedFrom(from: Int) {
        if (offset == null) offset = from
    }

    /** The kept place, which is forgotten as it is handed over. */
    fun take(): Int? = offset.also { offset = null }

    fun clear() {
        offset = null
    }

    /** Forgets the place once the page in front of the reader holds it; [length] is the book's. */
    fun reached(start: Int, end: Int, length: Int) {
        val kept = offset ?: return
        if (kept in start until end || (kept >= length && end >= length)) offset = null
    }
}
