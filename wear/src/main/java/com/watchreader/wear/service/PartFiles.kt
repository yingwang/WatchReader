package com.watchreader.wear.service

import java.io.File

/**
 * Where a book being received is written before it is renamed into place: "<id>.part" beside the
 * books. A transfer that ends without reaching its catch block, because the process was killed,
 * the watch restarted or a long book ran the memory out (an Error, which is not caught), leaves
 * its .part behind until the same book is sent again, and a watch has little room to spare.
 */
object PartFiles {
    private const val SUFFIX = ".part"

    fun forBook(booksDir: File, bookId: String): File = File(booksDir, bookId + SUFFIX)

    /**
     * Deletes every .part file in [booksDir] and says how many went. Only for the moment the app
     * starts, before a transfer can have begun: one under way is written to a .part as well.
     */
    fun clearLeftovers(booksDir: File): Int =
        booksDir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }.orEmpty().count { it.delete() }
}
