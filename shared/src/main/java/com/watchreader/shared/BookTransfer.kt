package com.watchreader.shared

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire format of a book channel: one line of metadata JSON, a newline, then the UTF-8 text.
 *
 * Putting the metadata inside the stream means a transfer is self-contained: the watch does not
 * have to pair a separately delivered message with a channel that opens "soon after", and two
 * books sent back to back cannot swap titles.
 */
object BookTransfer {
    private const val MAX_HEADER_BYTES = 64 * 1024

    fun writeHeader(output: OutputStream, meta: BookMetadata) {
        var header = meta.toJson().toByteArray(Charsets.UTF_8)
        // Contents are optional. Large EPUB navigation trees must not break book transfer,
        // including transfers to older watches with the same 64 KiB header limit.
        if (header.size > MAX_HEADER_BYTES) header = meta.copy(tocJson = null).toJson().toByteArray(Charsets.UTF_8)
        require(header.size <= MAX_HEADER_BYTES) { "Book header is too large" }
        output.write(header)
        output.write('\n'.code)
    }

    /**
     * The contents [writeHeader] had to leave out of this book's header, as the payload of a
     * [DataLayerPaths.CONTENTS_PATH] message sent once the watch has the book; null when the
     * header carried them, or when they are too large even for a message of their own.
     *
     * A web novel of a thousand chapters or so has a contents list past the header's limit.
     * Without it the watch shows the book with no chapters at all, since it no longer works
     * them out from the text, and a message can carry half as much again as a header can.
     */
    fun contentsLeftOut(meta: BookMetadata): ByteArray? {
        val tocJson = meta.tocJson ?: return null
        if (meta.toJson().toByteArray(Charsets.UTF_8).size <= MAX_HEADER_BYTES) return null
        val payload = BookContents(meta.id, tocJson).toJson().toByteArray(Charsets.UTF_8)
        return payload.takeIf { it.size <= MAX_CONTENTS_MESSAGE_BYTES }
    }

    /**
     * The Data Layer refuses a message over 100 KB, and the bound kept here leaves room below
     * that for whatever the layer adds around the payload.
     */
    const val MAX_CONTENTS_MESSAGE_BYTES = 90 * 1000

    /** Reads the header line and leaves [input] positioned at the first byte of the text. */
    @Throws(IOException::class)
    fun readHeader(input: InputStream): BookMetadata {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Stream ended before the book header was complete")
            if (b == '\n'.code) break
            buffer.write(b)
            if (buffer.size() > MAX_HEADER_BYTES) throw IOException("Book header is too large")
        }
        val json = buffer.toString(Charsets.UTF_8.name())
        return try {
            BookMetadata.fromJson(json)
        } catch (e: Exception) {
            throw IOException("Malformed book header: ${e.message}")
        }
    }
}
