package com.watchreader.shared.bookmark

import java.security.MessageDigest

/** One register per book and UTF-16 position. Deletions remain as tombstones for offline peers. */
data class Bookmark(
    val bookId: String,
    val offset: Int,
    val excerpt: String,
    val revision: Long,
    val writerId: String,
    val deleted: Boolean = false,
) {
    init {
        require(bookId.isNotBlank() && bookId.length <= 512)
        require(offset >= 0 && revision > 0 && revision < Long.MAX_VALUE)
        require(writerId.isNotBlank() && writerId.length <= 128)
        require(excerpt.length <= 512)
    }

    val key: String get() = bookKey(bookId) + "/" + offset

    companion object {
        fun bookKey(bookId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(bookId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        // Total ordering makes delivery order irrelevant, including equal-clock conflicts.
        private val order = compareBy<Bookmark>({ it.revision }, { it.deleted }, { it.writerId }, { it.excerpt })

        fun merge(previous: Bookmark?, incoming: Bookmark): Bookmark {
            if (previous == null) return incoming
            require(previous.bookId == incoming.bookId && previous.offset == incoming.offset)
            return if (order.compare(previous, incoming) >= 0) previous else incoming
        }

        fun visible(records: Collection<Bookmark>, bookId: String): List<Bookmark> =
            records.filter { it.bookId == bookId && !it.deleted }.sortedBy { it.offset }
    }
}
