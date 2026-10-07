package com.watchreader.mobile.util

import com.watchreader.shared.Chapter
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A FictionBook 2 reader: the title and authors from the book's description, its bodies as plain
 * text with a chapter for every titled section, and its cover picture. FB2 is the e-book format
 * of the Russian-speaking internet, handed round as often zipped on its own, as .fb2.zip, as not,
 * and saved in Windows-1251 about as often as in UTF-8; the encoding is the one the XML prolog
 * names, checked against the bytes the way a .txt is.
 *
 * Like [EpubParser] it reads the markup with a small scanner rather than an XML parser, since a
 * book that is not quite well-formed is still a book: an HTML entity nothing declares, a tag left
 * open, an ampersand nobody escaped.
 */
object Fb2Parser {
    class Fb2(val title: String, val author: String, val text: String, val cover: ByteArray?, val chapters: List<Chapter>)

    /** The most a zipped book may unpack to: as much as a book file brought in as it is may be. */
    const val MAX_UNPACKED_BYTES = 20L * 1024 * 1024

    /** Whether [bytes] are an FB2 book, as XML or zipped on its own. */
    fun looksLikeFb2(bytes: ByteArray): Boolean =
        if (EpubParser.looksLikeEpub(bytes)) zipped(bytes) else FICTION_BOOK.containsMatchIn(head(bytes))

    /**
     * FictionBook as the document's root: nothing before it but the prolog, comments and a
     * doctype. A plain-text book that merely mentions the element is not one.
     */
    private val FICTION_BOOK = Regex(
        """^\s*(?:<\?.*?\?>\s*|<!--.*?-->\s*|<!DOCTYPE[^>]*>\s*)*<(?:\w+:)?FictionBook\b""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parse(bytes: ByteArray): Fb2 {
        val xml = EpubParser.markup(if (EpubParser.looksLikeEpub(bytes)) unzip(bytes) else bytes)
        // Only the book's own description counts: document-info names whoever made the file, and
        // src-title-info the original of a translation.
        val info = TITLE_INFO.find(xml)?.groupValues?.get(1).orEmpty()
        val title = BOOK_TITLE.find(info)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
        val author = AUTHOR.findAll(info).map { person(it.groupValues[1]) }.filter { it.isNotEmpty() }
            .distinct().joinToString(", ")
        val coverId = COVERPAGE.find(info)?.groupValues?.get(1)
            ?.let { IMAGE.find(it)?.value }?.let { HREF.find(it)?.groupValues?.get(1) }
            ?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
        val reader = BodyReader(xml, coverId)
        reader.read()
        return Fb2(title, author, reader.text(), reader.cover, reader.chapters)
    }

    /**
     * Whether an archive is a zipped FB2 book. An EPUB is a ZIP as well, and gives itself away by
     * the mimetype and META-INF files it opens with long before a reader would reach anything else.
     */
    private fun zipped(bytes: ByteArray): Boolean = runCatching {
        ZipEntries.read(bytes) { files ->
            files.map { it.first }
                .firstOrNull { it.endsWith(".fb2", ignoreCase = true) || it == "mimetype" || it.startsWith("META-INF/", ignoreCase = true) }
                ?.endsWith(".fb2", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    private fun unzip(bytes: ByteArray): ByteArray = ZipEntries.read(bytes) { files ->
        val input = files.firstOrNull { it.first.endsWith(".fb2", ignoreCase = true) }?.second
            ?: throw IllegalArgumentException("This archive holds no .fb2 book")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_UNPACKED_BYTES) throw IllegalArgumentException("This FB2 book unpacks to more than 20 MB")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    /** The front of a file as text, far enough in to find the root element behind the prolog. */
    private fun head(bytes: ByteArray): String {
        val n = minOf(bytes.size, HEAD_BYTES)
        fun starts(vararg mark: Int) = n >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
        return when {
            starts(0xFF, 0xFE) -> String(bytes, 2, (n - 2) and 1.inv(), Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, (n - 2) and 1.inv(), Charsets.UTF_16BE)
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, n - 3, Charsets.ISO_8859_1)
            else -> String(bytes, 0, n, Charsets.ISO_8859_1)
        }
    }

    private const val HEAD_BYTES = 4096

    private val TITLE_INFO = Regex("""<(?:\w+:)?title-info\b[^>]*>(.*?)</(?:\w+:)?title-info\s*>""", RegexOption.DOT_MATCHES_ALL)
    private val BOOK_TITLE = Regex("""<(?:\w+:)?book-title\b[^>]*>(.*?)</(?:\w+:)?book-title\s*>""", RegexOption.DOT_MATCHES_ALL)
    private val AUTHOR = Regex("""<(?:\w+:)?author\b[^>]*>(.*?)</(?:\w+:)?author\s*>""", RegexOption.DOT_MATCHES_ALL)
    private val COVERPAGE = Regex("""<(?:\w+:)?coverpage\b[^>]*>(.*?)</(?:\w+:)?coverpage\s*>""", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE = Regex("""<(?:\w+:)?image\b[^>]*>""")

    /** The link of an image, under whatever prefix the book bound the XLink namespace to. */
    private val HREF = Regex("""(?:[\w-]+:)?href\s*=\s*["']([^"']*)["']""")

    /** An author as the description gives one: first, middle and last name, or else a pen name. */
    private fun person(markup: String): String {
        fun part(name: String) = Regex("""<(?:\w+:)?$name\b[^>]*>(.*?)</(?:\w+:)?$name\s*>""", RegexOption.DOT_MATCHES_ALL)
            .find(markup)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
        val full = listOf("first-name", "middle-name", "last-name").map { part(it) }.filter { it.isNotEmpty() }.joinToString(" ")
        return full.ifEmpty { part("nickname") }
    }

    /** Markup reduced to its words, on one line. */
    private fun plain(markup: String): String =
        EpubParser.decodeEntities(markup.replace(Regex("<[^>]*>"), " ")).replace(Regex("[\\s ]+"), " ").trim()

    /**
     * Walks the document once, front to back, writing out the text of its bodies as it goes. A
     * paragraph, a line of verse, a subtitle or an epigraph's author is a block of its own: the
     * lines of a stanza and of a title follow one another directly, everything else has a blank
     * line between. A section's title is written where it stands and names a chapter there, so
     * nested sections give a part and then its chapters, the way an EPUB's nested contents do.
     * The notes a book keeps in bodies of their own follow the story, under one chapter for all
     * of them rather than one for every note.
     */
    private class BodyReader(private val xml: String, private val coverId: String?) {
        private val out = StringBuilder()
        val chapters = ArrayList<Chapter>()
        var cover: ByteArray? = null
            private set

        /** The text of the block being read, written out once the block ends. */
        private val block = StringBuilder()
        private var pending = NONE

        /** The elements open within the current body, innermost last. */
        private val open = ArrayList<String>()
        private var inBody = false
        private var mainBody = false

        /** How deep the title being read as a chapter's name sits, or -1 when there is none. */
        private var titleDepth = -1
        private var titleStart = -1
        private val titleLines = ArrayList<String>()

        fun text(): String = out.toString().trimEnd()

        fun read() {
            var i = 0
            while (i < xml.length) {
                var lt = xml.indexOf('<', i)
                // A < that opens no tag is text someone forgot to escape.
                while (lt >= 0 && lt + 1 < xml.length && !opensMarkup(xml[lt + 1])) lt = xml.indexOf('<', lt + 1)
                val stop = if (lt < 0) xml.length else lt
                if (inBody && stop > i) block.append(clean(EpubParser.decodeEntities(xml.substring(i, stop))))
                if (lt < 0) break
                i = when {
                    xml.startsWith("<!--", lt) -> past(xml.indexOf("-->", lt + 4), 3)
                    xml.startsWith("<![CDATA[", lt) -> {
                        val end = xml.indexOf("]]>", lt + 9).let { if (it < 0) xml.length else it }
                        if (inBody) block.append(clean(xml.substring(lt + 9, end)))
                        past(end, 3)
                    }
                    xml.startsWith("<?", lt) || xml.startsWith("<!", lt) -> past(xml.indexOf('>', lt), 1)
                    else -> tag(lt)
                }
            }
            if (inBody) endBody()
        }

        private fun opensMarkup(c: Char) = c.isLetter() || c == '/' || c == '!' || c == '?'

        private fun past(index: Int, length: Int) = if (index < 0) xml.length else index + length

        /** Takes the tag starting at [lt] and returns where reading carries on. */
        private fun tag(lt: Int): Int {
            val end = tagEnd(lt)
            if (end >= xml.length) return xml.length
            val tag = xml.substring(lt, end)
            val closing = tag.startsWith("</")
            val name = localName(tag, if (closing) 2 else 1)
            val selfClosing = tag.endsWith("/")
            when {
                name == "binary" && !closing -> {
                    // Pictures are kept as base64 at the end of the file, and only the cover is wanted.
                    val close = BINARY_END.find(xml, end + 1)?.range?.first ?: xml.length
                    if (coverId != null && cover == null && EpubParser.attr(tag, "id") == coverId) {
                        cover = picture(xml.substring(end + 1, close))
                    }
                    return close
                }
                name == "body" -> if (closing) endBody() else startBody(tag, selfClosing)
                !inBody -> {}
                closing -> end(name)
                else -> {
                    start(name)
                    if (selfClosing) end(name)
                }
            }
            return end + 1
        }

        /** Where the tag starting at [lt] closes, a > inside a quoted value aside. */
        private fun tagEnd(lt: Int): Int {
            var quote = 0.toChar()
            var j = lt + 1
            while (j < xml.length) {
                val c = xml[j]
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '>') {
                    return j
                }
                j++
            }
            return xml.length
        }

        private fun startBody(tag: String, empty: Boolean) {
            if (inBody) endBody()
            if (empty) return
            inBody = true
            mainBody = EpubParser.attr(tag, "name")?.lowercase() !in NOTES
            open.clear()
            need(PARAGRAPH)
        }

        private fun endBody() {
            end(open.firstOrNull() ?: "")
            flush()
            open.clear()
            titleDepth = -1
            inBody = false
        }

        private fun start(name: String) {
            val parent = open.lastOrNull()
            if (name in BLOCKS) {
                flush()
                val follows = (name == "v" && parent == "stanza") || (name == "p" && parent == "title") || name == "tr"
                need(if (follows) LINE else PARAGRAPH)
            } else if ((name == "td" || name == "th") && block.isNotBlank()) {
                block.append(' ')
            }
            // A section's title names a chapter; so does the title of a body of notes, while the
            // title the story's own body opens with is the book's, already shown in the library.
            if (name == "title" && titleDepth < 0 && (if (mainBody) parent == "section" else parent == null)) {
                titleDepth = open.size
                titleStart = -1
                titleLines.clear()
            }
            open.add(name)
        }

        /** Ends [name] and whatever was left open inside it. */
        private fun end(name: String) {
            val at = open.lastIndexOf(name)
            if (at < 0) return
            while (open.size > at) {
                val ended = open.removeAt(open.size - 1)
                if (ended in BLOCKS) flush()
                if (ended == "title" && titleDepth == open.size) {
                    if (titleStart >= 0) chapters.add(Chapter(heading(titleLines), titleStart))
                    titleDepth = -1
                }
            }
        }

        private fun need(kind: Int) {
            pending = maxOf(pending, kind)
        }

        /** Writes out the block read so far, if it holds any words. */
        private fun flush() {
            val line = block.toString().replace(SPACES, " ").trim()
            block.setLength(0)
            if (line.isEmpty()) return
            if (out.isNotEmpty()) out.append(if (pending == LINE) "\n" else "\n\n")
            pending = NONE
            if (titleDepth >= 0) {
                if (titleStart < 0) titleStart = out.length
                titleLines.add(line)
            }
            out.append(line)
        }

        /** The cover picture from its base64, or null when it is not one or too large to keep. */
        private fun picture(base64: String): ByteArray? =
            runCatching { Base64.getMimeDecoder().decode(base64.trim()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= EpubParser.MAX_IMAGE_BYTES }
    }

    /** A tag's name without its namespace prefix, read from [from] on. */
    private fun localName(tag: String, from: Int): String {
        var end = from
        while (end < tag.length && !tag[end].isWhitespace() && tag[end] != '/' && tag[end] != '>') end++
        return tag.substring(from, end).substringAfter(':').lowercase()
    }

    /**
     * A chapter's name from the lines of its title: "Chapter 1" over "The Meeting" reads as
     * "Chapter 1. The Meeting", and a line that already ends in punctuation is followed by a space.
     */
    private fun heading(lines: List<String>): String = lines.fold("") { name, line ->
        when {
            name.isEmpty() -> line
            name.last() in ".!?:;,…—–-" -> "$name $line"
            else -> "$name. $line"
        }
    }.take(80)

    /** Whitespace of any kind, the no-break space included, as a single space; control codes gone. */
    private fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c.isWhitespace() || c == ' ' -> sb.append(' ')
                c.code < 0x20 || c.code in 0x7F..0x9F -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val SPACES = Regex(" {2,}")
    private val BINARY_END = Regex("""</(?:\w+:)?binary\s*>""")

    /** Elements that stand as blocks of their own; everything else runs on inside its block. */
    private val BLOCKS = setOf(
        "p", "v", "subtitle", "text-author", "date", "title", "epigraph", "poem", "stanza", "cite",
        "section", "annotation", "table", "tr", "empty-line",
    )

    /** The names a body of notes goes by; any other body is the story. */
    private val NOTES = setOf("notes", "comments", "footnotes")

    private const val NONE = 0
    private const val LINE = 1
    private const val PARAGRAPH = 2
}
