package com.watchreader.mobile.util

import com.watchreader.shared.Chapter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * A small EPUB 2/3 reader: container.xml -> OPF -> spine order -> XHTML -> plain text.
 * It is deliberately regex based (no XML parser on the hot path) but it does resolve hrefs the way
 * a real reader would: percent-decoded and normalised against the OPF's directory.
 */
object EpubParser {
    class Epub(val title: String, val text: String, val cover: ByteArray?, val chapters: List<Chapter>)

    fun looksLikeEpub(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /**
     * The most an EPUB may unpack to and still be held in memory. The 20 MB import cap is
     * measured on the compressed file, which says nothing about what a ZIP expands to.
     */
    const val MAX_UNPACKED_BYTES = 20L * 1024 * 1024

    /** Pictures only ever supply the cover, so past this much of them the rest stay in the file. */
    const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /** Everything inflated, kept or not, stops here: past it the file is a bomb, not a book. */
    const val MAX_INFLATED_BYTES = 8 * MAX_UNPACKED_BYTES

    /** Never opened by a text reader; skipped rather than kept, so embedded fonts cost nothing. */
    private val skippedExtensions = setOf(
        "ttf", "otf", "woff", "woff2", "eot", "css", "js", "smil", "pls",
        "mp3", "m4a", "aac", "ogg", "oga", "wav", "mp4", "m4v", "webm", "ogv", "mov",
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp")

    fun parse(inputStream: InputStream): Epub {
        val entries = readEntries(inputStream)

        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Not a valid EPUB file")
        val opfPath = Regex("""full-path="([^"]+)"""").find(container)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Cannot find OPF in EPUB")
        val opfContent = entries[opfPath]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Cannot read OPF")
        val opfDir = opfPath.substringBeforeLast("/", "")

        val title = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL)
            .find(opfContent)?.groupValues?.get(1)?.let { decodeEntities(it).trim() } ?: ""

        // manifest: id -> resolved zip path, split by what the entry can hold
        val manifest = HashMap<String, String>()
        val images = LinkedHashMap<String, String>()
        var declaredCoverId: String? = null
        Regex("""<item\b[^>]*?/?>""").findAll(opfContent).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            val mediaType = attr(tag, "media-type") ?: ""
            when {
                mediaType.contains("html") || mediaType.contains("xml") -> manifest[id] = resolve(opfDir, href)
                mediaType.startsWith("image/") -> {
                    images[id] = resolve(opfDir, href)
                    if (attr(tag, "properties")?.contains("cover-image") == true) declaredCoverId = id
                }
            }
        }
        if (declaredCoverId == null) {
            declaredCoverId = Regex("""<meta\b[^>]*name="cover"[^>]*>""").find(opfContent)
                ?.let { attr(it.value, "content") }
        }

        val spine = Regex("""<itemref\b[^>]*?/?>""").findAll(opfContent)
            .mapNotNull { attr(it.value, "idref") }
            .toList()

        val result = StringBuilder()
        val chapters = ArrayList<Chapter>()
        val docStart = HashMap<String, Int>()
        for (idref in spine) {
            val path = manifest[idref] ?: continue
            val html = entries[path]?.toString(Charsets.UTF_8) ?: continue
            val text = htmlToText(html)
            if (text.isNotBlank()) {
                docStart[path] = result.length
                // One spine document is one chapter; its own heading names it, else its first line.
                val heading = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .find(html)?.groupValues?.get(1)?.let { htmlToText(it) }?.trim()
                val name = heading?.takeIf { it.isNotBlank() }
                    ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (name.isNotBlank()) chapters.add(Chapter(name.take(80), result.length))
                result.append(text).append("\n\n")
            }
        }
        // The book's own table of contents beats guessing from headings, when it has one.
        val navPath = Regex("""<item\b[^>]*?/?>""").findAll(opfContent)
            .mapNotNull { tag ->
                val href = attr(tag.value, "href") ?: return@mapNotNull null
                val type = attr(tag.value, "media-type") ?: ""
                when {
                    attr(tag.value, "properties")?.contains("nav") == true -> resolve(opfDir, href)
                    type == "application/x-dtbncx+xml" -> resolve(opfDir, href)
                    else -> null
                }
            }.firstOrNull()
        val declared = navPath?.let { entries[it] }?.toString(Charsets.UTF_8)?.let { nav ->
            tocEntries(nav, opfDir).mapNotNull { (name, target) ->
                docStart[target]?.let { Chapter(name, it) }
            }
        }.orEmpty()
        if (declared.size >= 2) {
            chapters.clear()
            chapters.addAll(declared)
        }

        val coverPath = images[declaredCoverId]
            ?: images.entries.firstOrNull { (id, path) ->
                id.contains("cover", true) || path.substringAfterLast('/').contains("cover", true)
            }?.value
        return Epub(title, result.toString().trim(), coverPath?.let { entries[it] }, chapters)
    }

    /**
     * Inflates the archive once, front to back, keeping only what the parser can use and only as
     * much of it as the caps allow. A ZipInputStream learns an entry's size only by inflating it,
     * so the caps are enforced while reading rather than checked up front.
     */
    private fun readEntries(inputStream: InputStream): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        val buffer = ByteArray(64 * 1024)
        var inflated = 0L
        var kept = 0L
        var images = 0L
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val extension = entry.name.substringAfterLast('.', "").lowercase()
                    val isImage = extension in imageExtensions
                    var out: ByteArrayOutputStream? = if (extension in skippedExtensions) null else ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        inflated += n
                        if (inflated > MAX_INFLATED_BYTES) throw IllegalArgumentException("This EPUB unpacks to more than 160 MB")
                        if (out == null) continue
                        if (isImage) {
                            images += n
                            if (images > MAX_IMAGE_BYTES) {
                                // one picture too many: this one is dropped, the text carries on
                                out = null
                                continue
                            }
                        } else {
                            kept += n
                            if (kept > MAX_UNPACKED_BYTES) throw IllegalArgumentException("This EPUB unpacks to more than 20 MB")
                        }
                        out.write(buffer, 0, n)
                    }
                    if (out != null) entries[entry.name] = out.toByteArray()
                }
                entry = zip.nextEntry
            }
        }
        return entries
    }

    /** Titles and targets from an EPUB 3 nav document or an EPUB 2 NCX, in reading order. */
    private fun tocEntries(nav: String, opfDir: String): List<Pair<String, String>> {
        val ncx = Regex("""<navPoint\b.*?</navPoint>""", RegexOption.DOT_MATCHES_ALL).findAll(nav).mapNotNull { point ->
            val label = Regex("""<text[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
                .find(point.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val src = Regex("""<content[^>]*\bsrc="([^"]+)""" + "\"")
                .find(point.value)?.groupValues?.get(1) ?: return@mapNotNull null
            decodeEntities(label).trim().take(80) to resolve(opfDir, src)
        }.toList()
        if (ncx.isNotEmpty()) return ncx
        return Regex("""<a\b[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(nav)
            .map { htmlToText(it.groupValues[2]).trim().take(80) to resolve(opfDir, it.groupValues[1]) }
            .filter { it.first.isNotBlank() }
            .toList()
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
        // Mark the breaks the markup asks for, so the ones the source file merely wrapped at can
        // be flattened away: a paragraph should reach the reader as one long line, not as the
        // typesetting of whoever produced the file.
        s = s.replace(Regex("<br[^>]*/?>", RegexOption.IGNORE_CASE), BREAK)
        s = s.replace(Regex("</(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|dd|dt|pre|table)>", RegexOption.IGNORE_CASE), PARAGRAPH)
        s = s.replace(Regex("<hr\\b[^>]*/?>", RegexOption.IGNORE_CASE), PARAGRAPH)
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s)
        s = s.replace('\u00a0', ' ')
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(PARAGRAPH, "\n\n").replace(BREAK, "\n")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** Stand in for the breaks the markup asked for while ordinary whitespace is collapsed. */
    private const val BREAK = "\u0001"
    private const val PARAGRAPH = "\u0002"

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
