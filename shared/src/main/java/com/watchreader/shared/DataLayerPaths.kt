package com.watchreader.shared

/**
 * Wearable Data Layer paths shared by the phone and the watch.
 *
 * A book travels over one ChannelClient channel whose path is [BOOK_CHANNEL_PREFIX] + book id;
 * the first line of the stream is the book's metadata (see [BookTransfer]) and the rest is the
 * UTF-8 text. Everything else is a MessageClient message.
 */
object DataLayerPaths {
    /** Phone -> watch channel carrying one book. Full path: "/book/<id>". */
    const val BOOK_CHANNEL_PREFIX = "/book/"

    /** Phone -> watch message, payload is the book id to remove. */
    const val DELETE_BOOK_PATH = "/delete_book"

    /** Watch -> phone message, payload is a [BookReceipt] JSON. */
    const val BOOK_RECEIVED_PATH = "/book_received"

    /** Watch -> phone message, payload is the id of a book the user deleted on the watch. */
    const val BOOK_REMOVED_PATH = "/book_removed"

    /** Either side -> the other, payload is a [ReadingProgress] JSON. The later reading wins. */
    const val PROGRESS_PATH = "/progress"

    /** Capabilities advertised by each side, used to find nodes that actually run the app. */
    const val WEAR_CAPABILITY = "watchreader_wear"
    const val PHONE_CAPABILITY = "watchreader_phone"

    fun bookChannelPath(bookId: String): String = BOOK_CHANNEL_PREFIX + bookId

    /** The book id carried by a channel path, or null when the path is not a book channel. */
    fun bookIdFromChannelPath(path: String): String? =
        if (path.startsWith(BOOK_CHANNEL_PREFIX) && path.length > BOOK_CHANNEL_PREFIX.length) {
            path.substring(BOOK_CHANNEL_PREFIX.length)
        } else {
            null
        }
}
