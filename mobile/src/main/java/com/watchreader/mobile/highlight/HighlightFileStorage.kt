package com.watchreader.mobile.highlight

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Where one book's highlights are kept. */
interface HighlightStorage {
    /** The saved highlights, or null when none were ever saved. Throws when the file cannot be read. */
    fun read(): List<Highlight>?
    fun write(highlights: List<Highlight>)
    fun delete()
}

/**
 * One JSON file per book, written whole each time: a book holds tens of highlights, perhaps a few
 * hundred, and rewriting them costs less than any scheme for patching a file in place.
 */
class HighlightFileStorage(private val file: File, private val bookId: String) : HighlightStorage {
    override fun read(): List<Highlight>? = if (file.exists()) HighlightCodec.decode(file.readText(Charsets.UTF_8)) else null

    override fun write(highlights: List<Highlight>) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create the highlights directory" }
        val pending = pendingFile(file)
        val bytes = HighlightCodec.encode(bookId, highlights).toByteArray(Charsets.UTF_8)
        FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
        // The rename either happens whole or not at all, so a write cut short by a full disk or
        // the process being killed leaves the previous file as it was, never half of a new one.
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete() = HighlightFiles.delete(file)
}

/** Where the phone keeps each book's highlights: filesDir/reader-highlights, one file per book. */
object HighlightFiles {
    private const val DIRECTORY = "reader-highlights"

    /**
     * Named by a hash of the book's id, as the bookmarks key their books, so an id never has to be
     * a safe file name.
     */
    fun fileFor(filesDir: File, bookId: String): File = File(File(filesDir, DIRECTORY), "${key(bookId)}.json")

    /** Removes a book's highlights, and a write that was cut short beside them. */
    fun delete(file: File) {
        pendingFile(file).delete()
        if (file.exists()) check(file.delete()) { "Cannot delete ${file.name}" }
    }

    internal fun key(bookId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bookId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun pendingFile(file: File) = File(file.parentFile, file.name + ".pending")
