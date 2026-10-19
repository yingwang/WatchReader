package com.watchreader.shared.bookmark

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Lives under filesDir/reader-bookmarks, independently of the book database and book deletion. */
class BookmarkFileStorage(private val file: File) : BookmarkStorage {
    override fun read(): BookmarkSnapshot? = if (file.exists()) BookmarkCodec.decodeSnapshot(file.readText()) else null

    override fun write(snapshot: BookmarkSnapshot) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create bookmark directory" }
        val pending = File(parent, file.name + ".pending")
        val bytes = BookmarkCodec.encodeSnapshot(snapshot).toByteArray(Charsets.UTF_8)
        FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
        // A failure leaves the previous file intact. Never fall back to delete-then-rename.
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
