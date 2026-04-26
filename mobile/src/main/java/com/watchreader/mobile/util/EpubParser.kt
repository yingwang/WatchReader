package com.watchreader.mobile.util

import java.io.InputStream
import java.util.zip.ZipInputStream

object EpubParser {

    fun parse(inputStream: InputStream): String {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }

        // Find OPF path from container.xml
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Not a valid EPUB file")
        val opfPath = Regex("""full-path="([^"]+)""").find(container)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Cannot find OPF in EPUB")

        val opfContent = entries[opfPath]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Cannot read OPF")
        val opfDir = opfPath.substringBeforeLast("/", "")

        // Parse manifest: id -> href
        val manifest = mutableMapOf<String, String>()
        Regex("""<item[^>]*?/?>""").findAll(opfContent).forEach { m ->
            val tag = m.value
            val id = Regex("""id="([^"]*)""").find(tag)?.groupValues?.get(1) ?: return@forEach
            val href = Regex("""href="([^"]*)""").find(tag)?.groupValues?.get(1) ?: return@forEach
            val mediaType = Regex("""media-type="([^"]*)""").find(tag)?.groupValues?.get(1) ?: ""
            if (mediaType.contains("html") || mediaType.contains("xml")) {
                manifest[id] = href
            }
        }

        // Parse spine: ordered list of idref
        val spine = mutableListOf<String>()
        Regex("""<itemref[^>]*?/?>""").findAll(opfContent).forEach { m ->
            val idref = Regex("""idref="([^"]*)""").find(m.value)?.groupValues?.get(1) ?: return@forEach
            spine.add(idref)
        }

        // Read chapters in spine order, convert HTML to text
        val result = StringBuilder()
        for (idref in spine) {
            val href = manifest[idref] ?: continue
            val path = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
            val html = entries[path]?.toString(Charsets.UTF_8) ?: continue
            val text = htmlToText(html)
            if (text.isNotBlank()) {
                result.appendLine(text)
                result.appendLine()
            }
        }
        return result.toString().trim()
    }

    private fun htmlToText(html: String): String {
        var s = html
        // Remove style/script blocks
        s = s.replace(Regex("<(style|script)[^>]*>.*?</(style|script)>", RegexOption.DOT_MATCHES_ALL), "")
        // Block elements → newlines
        s = s.replace(Regex("<br[^>]*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</(p|div|h[1-6]|li|tr|blockquote)>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<(p|div|h[1-6])[^>]*>", RegexOption.IGNORE_CASE), "\n")
        // Strip remaining tags
        s = s.replace(Regex("<[^>]+>"), "")
        // Decode HTML entities
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&mdash;", "\u2014").replace("&ndash;", "\u2013")
            .replace("&hellip;", "\u2026").replace("&lsquo;", "\u2018")
            .replace("&rsquo;", "\u2019").replace("&ldquo;", "\u201C")
            .replace("&rdquo;", "\u201D")
        s = s.replace(Regex("&#(\\d+);")) {
            runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault("")
        }
        s = s.replace(Regex("&#x([0-9a-fA-F]+);")) {
            runCatching { it.groupValues[1].toInt(16).toChar().toString() }.getOrDefault("")
        }
        // Clean whitespace
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }
}
