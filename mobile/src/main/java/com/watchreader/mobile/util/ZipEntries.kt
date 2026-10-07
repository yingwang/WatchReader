package com.watchreader.mobile.util

import com.watchreader.shared.TextNormalizer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** The files in a ZIP archive held in memory, as the EPUB and FB2 readers take them. */
internal object ZipEntries {
    /**
     * Hands [block] every file in the archive, name and contents, front to back. Each file's
     * stream is good only until the next one is taken.
     *
     * The archive is read by the header in front of each file first, which works on an archive
     * cut short as far as it goes. Some tools store a file uncompressed and write its size after
     * it, as a few EPUB makers do with the mimetype file, and that reading cannot step past such
     * a file; the archive is then read again by its central directory, which lists every file's
     * size and place whatever the headers say. Refusals [block] throws are passed on as they are.
     */
    fun <T> read(bytes: ByteArray, block: (Sequence<Pair<String, InputStream>>) -> T): T {
        val failure = try {
            return block(streamed(bytes))
        } catch (e: IOException) {
            e
        }
        val listed = listed(bytes) ?: throw IllegalArgumentException(DAMAGED, failure)
        return try {
            block(listed)
        } catch (e: IOException) {
            throw IllegalArgumentException(DAMAGED, e)
        }
    }

    const val DAMAGED = "This file is damaged and cannot be read"

    private fun streamed(bytes: ByteArray): Sequence<Pair<String, InputStream>> = sequence {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                // A name that is not UTF-8 stops the stream reader dead; the directory reading
                // after it makes what it can of the name.
                val entry = try {
                    zip.nextEntry
                } catch (e: IllegalArgumentException) {
                    throw ZipException("A file name in the archive is not UTF-8")
                } ?: break
                if (!entry.isDirectory) yield(entry.name to zip)
            }
        }
    }

    /**
     * A file name as the archive stores it. The format says UTF-8 only where a flag says so, and
     * otherwise means the code page of the computer the archive was made on: an archive made on
     * a Chinese Windows names its files in GBK, while the book inside links to them in Unicode.
     */
    private fun fileName(bytes: ByteArray, at: Int, length: Int): String {
        val raw = bytes.copyOfRange(at, at + length)
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        }.getOrElse { TextNormalizer.decodeAsIs(raw).text }
    }

    /** The files the central directory lists; null when there is no readable directory. */
    private fun listed(bytes: ByteArray): Sequence<Pair<String, InputStream>>? {
        // The directory's end record closes the archive, followed only by a comment of up to 64 KB.
        var end = bytes.size - END_RECORD
        val lowest = maxOf(0, bytes.size - END_RECORD - 0xFFFF)
        while (end >= lowest && int(bytes, end) != END_SIGNATURE) end--
        if (end < lowest) return null
        val count = short(bytes, end + 10)
        var at = int(bytes, end + 16).toLong() and 0xFFFFFFFFL
        val files = ArrayList<Pair<String, () -> InputStream>>()
        repeat(count) {
            if (at < 0 || at + DIRECTORY_RECORD > bytes.size || int(bytes, at.toInt()) != DIRECTORY_SIGNATURE) return null
            val record = at.toInt()
            val method = short(bytes, record + 10)
            val size = int(bytes, record + 20).toLong() and 0xFFFFFFFFL
            val nameLength = short(bytes, record + 28)
            val skip = nameLength + short(bytes, record + 30) + short(bytes, record + 32)
            val header = int(bytes, record + 42).toLong() and 0xFFFFFFFFL
            if (record + DIRECTORY_RECORD + nameLength > bytes.size) return null
            val name = fileName(bytes, record + DIRECTORY_RECORD, nameLength)
            at += DIRECTORY_RECORD + skip
            if (name.endsWith("/") || header + LOCAL_RECORD > bytes.size) return@repeat
            val local = header.toInt()
            if (int(bytes, local) != LOCAL_SIGNATURE) return@repeat
            val start = local + LOCAL_RECORD + short(bytes, local + 26) + short(bytes, local + 28)
            if (start + size > bytes.size) return@repeat
            val length = size.toInt()
            when (method) {
                STORED -> files.add(name to { ByteArrayInputStream(bytes, start, length) })
                // A raw deflate stream wants one byte past its end before it will say it is done.
                DEFLATED -> files.add(name to {
                    val data = SequenceInputStream(ByteArrayInputStream(bytes, start, length), ByteArrayInputStream(ByteArray(1)))
                    InflaterInputStream(data, Inflater(true))
                })
            }
        }
        return files.asSequence().map { (name, open) -> name to open() }
    }

    private fun short(bytes: ByteArray, at: Int): Int =
        if (at < 0 || at + 2 > bytes.size) -1 else (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun int(bytes: ByteArray, at: Int): Int =
        if (at < 0 || at + 4 > bytes.size) 0 else short(bytes, at) or (short(bytes, at + 2) shl 16)

    private const val END_SIGNATURE = 0x06054b50
    private const val DIRECTORY_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val END_RECORD = 22
    private const val DIRECTORY_RECORD = 46
    private const val LOCAL_RECORD = 30
    private const val STORED = 0
    private const val DEFLATED = 8
}
