package com.watchreader.mobile.util

import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * A small EPUB 2/3 reader: container.xml -> OPF -> spine order -> XHTML -> plain text.
 * It is deliberately regex based (no XML parser on the hot path) but it does resolve hrefs the way
 * a real reader would: percent-decoded and normalised against the OPF's directory.
 */
object EpubParser {
    class Epub(val title: String, val text: String)

    fun looksLikeEpub(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun parse(inputStream: InputStream): Epub {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }

        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Not a valid EPUB file")
        val opfPath = Regex("""full-path="([^"]+)"""").find(container)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Cannot find OPF in EPUB")
        val opfContent = entries[opfPath]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Cannot read OPF")
        val opfDir = opfPath.substringBeforeLast("/", "")

        val title = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL)
            .find(opfContent)?.groupValues?.get(1)?.let { decodeEntities(it).trim() } ?: ""

        // manifest: id -> resolved zip path (only documents that can hold text)
        val manifest = HashMap<String, String>()
        Regex("""<item\b[^>]*?/?>""").findAll(opfContent).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            val mediaType = attr(tag, "media-type") ?: ""
            if (mediaType.contains("html") || mediaType.contains("xml")) {
                manifest[id] = resolve(opfDir, href)
            }
        }

        val spine = Regex("""<itemref\b[^>]*?/?>""").findAll(opfContent)
            .mapNotNull { attr(it.value, "idref") }
            .toList()

        val result = StringBuilder()
        for (idref in spine) {
            val path = manifest[idref] ?: continue
            val html = entries[path]?.toString(Charsets.UTF_8) ?: continue
            val text = htmlToText(html)
            if (text.isNotBlank()) {
                result.append(text).append("\n\n")
            }
        }
        return Epub(title, result.toString().trim())
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)
            ?: Regex("""\b$name\s*=\s*'([^']*)'""").find(tag)?.groupValues?.get(1)

    /** Joins an OPF-relative href to the OPF directory, decoding %20 and collapsing "../". */
    internal fun resolve(opfDir: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }.getOrDefault(href)
        val raw = if (opfDir.isEmpty()) decoded else "$opfDir/$decoded"
        val parts = ArrayList<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    internal fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("<(style|script|head)[^>]*>.*?</(style|script|head)>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        s = s.replace(Regex("<br[^>]*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|dd|dt|pre|table)>", RegexOption.IGNORE_CASE), "\n")
        // Opening block tags add nothing: one newline per closed block keeps paragraphs on
        // consecutive lines, which is what a watch-sized page wants; an empty <p></p> still
        // survives as a blank line for scene breaks.
        s = s.replace(Regex("<hr\\b[^>]*/?>", RegexOption.IGNORE_CASE), "\n\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s)
        s = s.replace(' ', ' ')
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private val namedEntities = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘",
        "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "copy" to "©",
        "reg" to "®", "trade" to "™", "middot" to "·", "laquo" to "«",
        "raquo" to "»", "shy" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    )

    internal fun decodeEntities(input: String): String =
        Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(input) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") -> codePoint(body.substring(2).toIntOrNull(16))
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
                else -> namedEntities[body] ?: m.value
            }
        }

    private fun codePoint(cp: Int?): String =
        if (cp == null || cp <= 0 || cp > 0x10FFFF) "" else String(Character.toChars(cp))
}
