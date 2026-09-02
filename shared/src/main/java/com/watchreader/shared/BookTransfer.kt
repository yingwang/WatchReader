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
        output.write(meta.toJson().toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
    }

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
