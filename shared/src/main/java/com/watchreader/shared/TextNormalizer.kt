package com.watchreader.shared

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Turns the bytes of a plain-text book into clean UTF-8 text.
 *
 * Detection order: byte-order mark, then strict UTF-8, then GB18030 (a superset of GBK, which is
 * what most Chinese .txt files in the wild are saved as), then ISO-8859-1 as a last resort so that
 * nothing is ever rejected. Line endings become "\n" and runs of blank lines collapse to one.
 */
object TextNormalizer {
    data class Decoded(val text: String, val charset: String)

    fun decode(bytes: ByteArray, declaredCharset: String? = null): Decoded {
        bom(bytes)?.let { (charset, skip) ->
            return Decoded(normalize(String(bytes, skip, bytes.size - skip, charset)), charset.name())
        }
        declaredCharset?.takeIf { it.isNotBlank() }?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()?.let { cs ->
                strict(bytes, cs)?.let { return Decoded(normalize(it), cs.name()) }
            }
        }
        strict(bytes, Charsets.UTF_8)?.let { return Decoded(normalize(it), "UTF-8") }
        val gb = Charset.forName("GB18030")
        strict(bytes, gb)?.let { return Decoded(normalize(it), gb.name()) }
        return Decoded(normalize(String(bytes, Charsets.ISO_8859_1)), "ISO-8859-1")
    }

    /** Collapses line endings and blank runs; also strips control characters that TTS engines choke on. */
    fun normalize(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(unified.length)
        var blankRun = 0
        for (line in unified.split('\n')) {
            val cleaned = line.trimEnd().filterNot { it.code < 0x20 && it != '\t' }
            if (cleaned.isBlank()) {
                blankRun++
                if (blankRun == 1) sb.append('\n')
            } else {
                blankRun = 0
                sb.append(cleaned).append('\n')
            }
        }
        return sb.toString().trim()
    }

    private fun bom(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8 to 3
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE to 2
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE to 2
        }
        return null
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
