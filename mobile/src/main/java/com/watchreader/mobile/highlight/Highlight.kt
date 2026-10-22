package com.watchreader.mobile.highlight

/**
 * The colours a passage can be marked in. [key] is how a colour is written to disk, so a file
 * stays readable whatever the enum is called; [argb] is the colour at full strength, as the
 * toolbar shows it. The page draws it see-through, so the text stays readable on it.
 */
enum class HighlightColor(val key: String, val argb: Long) {
    YELLOW("yellow", 0xFFF2CF3E),
    GREEN("green", 0xFF7CCB72),
    BLUE("blue", 0xFF6BAEEA),
    PINK("pink", 0xFFEE88B4);

    companion object {
        /** The first colour, for a highlight from a file that names none or one this version does not know. */
        val DEFAULT = YELLOW

        fun fromKey(key: String?): HighlightColor = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * A passage the reader marked on the phone, perhaps with a note. [start] and [end] are character
 * offsets into the book's text, the same coordinates as the reading place and bookmarks, so a
 * highlight stays on its words through every change of type size, spacing and margins. [quote]
 * is the text as it was when marked, which the list and the export show without loading the book.
 */
data class Highlight(
    val id: String,
    val start: Int,
    val end: Int,
    val quote: String,
    val color: HighlightColor,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(id.isNotBlank()) { "A highlight needs an id" }
        require(start in 0 until end) { "A highlight covers at least one character" }
    }

    val hasNote: Boolean get() = note.isNotBlank()

    /** Whether [offset] falls inside the highlighted characters. */
    operator fun contains(offset: Int): Boolean = offset in start until end

    companion object {
        /** Book order: where it starts, then where it ends, then which was made first. */
        val BOOK_ORDER: Comparator<Highlight> = compareBy({ it.start }, { it.end }, { it.createdAt }, { it.id })
    }
}
