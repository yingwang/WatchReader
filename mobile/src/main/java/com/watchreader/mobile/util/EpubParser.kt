package com.watchreader.mobile.util

import com.watchreader.shared.Chapter
import com.watchreader.shared.TextNormalizer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * A small EPUB 2/3 reader: container.xml -> OPF -> spine order -> XHTML -> plain text.
 * It is deliberately regex based (no XML parser on the hot path) but it does resolve hrefs the way
 * a real reader would: percent-decoded and normalised against the OPF's directory.
 *
 * It is also forgiving the way a real reader is, since books made by hand or by old tools bend
 * the rules a strict reader would refuse them over: files named in another case than their links,
 * hrefs percent-encoded on one side only, tags with a namespace prefix, HTML entities in XHTML
 * with no DTD to declare them, documents in Windows-1251 or GBK, a contents file that only the
 * spine names, and archives a stream reader cannot step through.
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

    fun parse(inputStream: InputStream): Epub = parse(inputStream.readBytes())

    fun parse(bytes: ByteArray): Epub {
        val archive = Archive(readEntries(bytes))

        // The container names the package document. A book whose container is missing, or names
        // a file the archive does not hold, still has its package document somewhere in it.
        val container = archive["META-INF/container.xml"]?.let { markup(it) }
        val opfPath = container?.let { packagePath(it) }?.takeIf { archive[it] != null }
            ?: archive.paths.filter { it.endsWith(".opf", ignoreCase = true) }.minByOrNull { it.count { c -> c == '/' } }
            ?: throw IllegalArgumentException(if (container == null) "Not a valid EPUB file" else "Cannot find OPF in EPUB")
        val opfContent = markup(archive[opfPath] ?: throw IllegalArgumentException("Cannot read OPF"))
        val opfDir = opfPath.substringBeforeLast("/", "")

        val title = Regex("""<(?:\w+:)?title\b[^>]*>(.*?)</(?:\w+:)?title\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(opfContent)?.groupValues?.get(1)?.let { decodeEntities(it).trim() } ?: ""

        // manifest: id -> the file at its resolved zip path
        val items = LinkedHashMap<String, Item>()
        Regex("""<(?:\w+:)?item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opfContent).forEach { m ->
            val id = attr(m.value, "id") ?: return@forEach
            val href = attr(m.value, "href")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val mediaType = attr(m.value, "media-type")?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val properties = attr(m.value, "properties").orEmpty().split(' ').filter { it.isNotEmpty() }
            items.putIfAbsent(id, Item(resolve(opfDir, href), mediaType, properties))
        }
        val images = items.filterValues { it.isPicture }

        val spineTag = Regex("""<(?:\w+:)?spine\b[^>]*>""", RegexOption.IGNORE_CASE).find(opfContent)
        val spine = Regex("""<(?:\w+:)?itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opfContent)
            .mapNotNull { tag -> attr(tag.value, "idref")?.let { items[it] } }
            .filter { it.isDocument }
            .distinctBy { fold(it.path) }
            .toList()
            // A package with no usable spine still lists its pages, and lists them in reading order.
            .ifEmpty { items.values.filter { it.isDocument } }

        // A shop's copy protection leaves the archive readable and scrambles the chapters inside
        // it, which would otherwise be read as a book of noise.
        val encrypted = encryptedPaths(archive["META-INF/encryption.xml"]?.let { markup(it) }).mapTo(HashSet()) { fold(it) }
        if (spine.any { fold(it.path) in encrypted }) {
            throw IllegalArgumentException("This book is copy-protected (DRM), so its text cannot be read. Only DRM-free epubs can be added.")
        }

        // The book's own table of contents beats guessing from headings, when it has one. An EPUB 3
        // book has a navigation document and usually an NCX as well; an EPUB 2 book has only the
        // NCX, which the spine names by id and older tools label as plain XML or leave out of the
        // manifest altogether. The first of them that lists anything is the one used.
        val navDocuments = items.values.filter { "nav" in it.properties }.map { it.path }
        val ncxFiles = listOfNotNull(spineTag?.let { attr(it.value, "toc") }?.let { items[it]?.path }) +
            items.values.filter { "ncx" in it.mediaType || it.path.endsWith(".ncx", ignoreCase = true) }.map { it.path } +
            archive.paths.filter { it.endsWith(".ncx", ignoreCase = true) }
        var toc: List<Pair<String, String>> = emptyList()
        for (path in (navDocuments + ncxFiles).distinctBy { fold(it) }) {
            // Targets inside the contents document are relative to that document, not to the OPF.
            val found = archive[path]?.let { tocEntries(markup(it), path.substringBeforeLast('/', "")) }.orEmpty()
            if (found.isNotEmpty()) {
                toc = found
                break
            }
        }
        val tocTitles = toc.mapTo(HashSet()) { it.first.trim().lowercase() }.apply { remove("") }

        val documents = spine.mapNotNull { item ->
            val html = archive[item.path]?.let { markup(it) } ?: return@mapNotNull null
            val (text, anchors) = textWithAnchors(html)
            if (text.isBlank()) null else Document(item.path, html, text, anchors)
        }
        // A book that prints its contents carries a page holding nothing but the titles the
        // contents already names. Reading it means reading the whole list before the book starts,
        // and every heading on it competes with the chapter it points at, so it is not text.
        val navigation = navDocuments.mapTo(HashSet()) { fold(it) }
        val listing = documents.filter { fold(it.path) in navigation || isContentsPage(it.text, tocTitles) }
            .mapTo(HashSet()) { it.path }
        val readable = if (listing.size < documents.size) documents.filterNot { it.path in listing } else documents

        val result = StringBuilder()
        val chapters = ArrayList<Chapter>()
        val docStart = HashMap<String, Int>()
        val anchorStart = HashMap<String, Int>()
        // Where each anchor lands wherever it is, for contents that name the wrong file for it;
        // null once two documents use the same id, and it no longer points anywhere in particular.
        val anchorAnywhere = HashMap<String, Int?>()
        for ((path, html, text, anchors) in readable) {
            val key = fold(path)
            docStart.putIfAbsent(key, result.length)
            for ((id, offset) in anchors) {
                anchorStart.putIfAbsent("$key#$id", result.length + offset)
                anchorAnywhere[id] = if (anchorAnywhere.containsKey(id)) null else result.length + offset
            }
            // One spine document is one chapter; its own heading names it, else its first line.
            // A file Calibre split off the end of a long chapter has neither: its first line falls
            // wherever the split did, and it is no chapter of its own.
            val heading = HEADING.find(html)?.groupValues?.get(1)?.let { htmlToText(it) }?.trim()
            val name = heading?.takeIf { it.isNotBlank() }
                ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeUnless { SPLIT_CONTINUATION.containsMatchIn(path) }
                ?: ""
            if (name.isNotBlank()) chapters.add(Chapter(name.take(80), result.length))
            result.append(text).append("\n\n")
        }
        // A book kept in one document names its chapters by anchor, so the anchor is what places
        // them; without it every entry would share the one offset the document itself starts at.
        // Contents made before Calibre split a long file still name the file it was, or the
        // first part of it for an anchor that went to a later part.
        val declared = toc.mapNotNull { (name, target) ->
            val path = fold(target.substringBefore('#'))
            val id = target.substringAfter('#', "")
            val anchored = if (id.isEmpty()) null else anchorStart["$path#$id"] ?: anchorAnywhere[id]
            (anchored ?: docStart[path] ?: docStart[firstSplit(path)])?.let { Chapter(name, it) }
        }
        if (declared.size >= 2) {
            chapters.clear()
            chapters.addAll(declared)
        }

        val coverPath = coverItem(opfContent, opfDir, items, images)?.path
            ?: images.entries.firstOrNull { (id, item) ->
                id.contains("cover", true) || item.path.substringAfterLast('/').contains("cover", true)
            }?.value?.path
        // Every document's text starts on its first character, so only the separator after the
        // last one is left to go; a trim at the front would move every chapter and anchor offset.
        return Epub(title, result.toString().trimEnd(), coverPath?.let { archive[it] }, chapters)
    }

    /** A file the manifest lists: where it is in the archive, what it says it is, and its roles. */
    private class Item(val path: String, val mediaType: String, val properties: List<String>) {
        private val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

        /** A picture the phone can draw, which is all a cover can be. */
        val isPicture = if (mediaType.startsWith("image/")) "svg" !in mediaType else extension in PICTURE_EXTENSIONS

        /**
         * A page of the book. Older books label their pages text/html, as XML, with the media type
         * of OEB 1, or with nothing a reader knows, and their names are what is left to go by.
         */
        val isDocument = !mediaType.startsWith("image/") && "ncx" !in mediaType &&
            ("html" in mediaType || "xml" in mediaType || extension in DOCUMENT_EXTENSIONS)
    }

    private val PICTURE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val DOCUMENT_EXTENSIONS = setOf("xhtml", "html", "htm", "xht", "xml", "shtml")

    /** The cover the package declares, by EPUB 3 property or by the EPUB 2 meta naming its id. */
    private fun coverItem(opf: String, opfDir: String, items: Map<String, Item>, images: Map<String, Item>): Item? {
        images.values.firstOrNull { "cover-image" in it.properties }?.let { return it }
        val named = Regex("""<(?:\w+:)?meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)
            .firstOrNull { attr(it.value, "name").equals("cover", ignoreCase = true) }
            ?.let { attr(it.value, "content")?.trim() } ?: return null
        // Some books give the picture's path where its id belongs.
        return items[named]?.takeIf { it.isPicture }
            ?: images.values.firstOrNull { fold(it.path) == fold(resolve(opfDir, named)) }
    }

    /**
     * The package document a container names. A container may list other renditions beside the
     * book, and the one in OPF form is the one this reader can follow.
     */
    private fun packagePath(container: String): String? {
        val rootfiles = Regex("""<(?:\w+:)?rootfile\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(container).map { it.value }.toList()
        val chosen = rootfiles.firstOrNull { tag ->
            attr(tag, "media-type")?.contains("oebps-package") == true || attr(tag, "full-path")?.endsWith(".opf", true) == true
        } ?: rootfiles.firstOrNull()
        return chosen?.let { attr(it, "full-path") }?.trim()?.takeIf { it.isNotEmpty() }?.let { resolve("", it) }
    }

    /** The first heading in a document, as markup. */
    private val HEADING = Regex(
        """<(?:\w+:)?h[1-6]\b[^>]*>(.*?)</(?:\w+:)?h[1-6]\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** A later part of a file Calibre split: chapter_split_001.html and on. */
    private val SPLIT_CONTINUATION = Regex("""_split_0*[1-9]\d*\.[^./]+$""", RegexOption.IGNORE_CASE)

    /** Where Calibre puts the first part of a file it split: chapter.html becomes chapter_split_000.html. */
    private fun firstSplit(path: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot <= path.lastIndexOf('/')) path + "_split_000" else path.substring(0, dot) + "_split_000" + path.substring(dot)
    }

    /**
     * The archive's files, found the way a forgiving reader finds them. Books made by hand or by
     * old tools name a file one way and link to it another: in another case, percent-encoded in
     * the archive or in the link but not both, or with the backslashes of Windows in the archive.
     * A path is looked up as it is first and folded to lower case after.
     */
    private class Archive(entries: Map<String, ByteArray>) {
        private val exact = HashMap<String, ByteArray>()
        private val folded = HashMap<String, ByteArray>()

        /** Every file's path, in the order the archive holds them. */
        val paths = ArrayList<String>()

        init {
            for ((name, bytes) in entries) {
                val path = normalise(name.replace('\\', '/'))
                paths.add(path)
                for (key in listOf(path, normalise(percentDecoded(path)))) {
                    exact.putIfAbsent(key, bytes)
                    folded.putIfAbsent(fold(key), bytes)
                }
            }
        }

        operator fun get(path: String): ByteArray? = exact[path] ?: folded[fold(path)]
    }

    private fun fold(path: String): String = path.lowercase(Locale.ROOT)

    /**
     * A document's text in the encoding it declares, in its XML declaration or an HTML meta tag,
     * or else the one it turns out to be in. Books made before EPUB settled on UTF-8 were often
     * saved in the code page of their language, Windows-1251 or GBK, and a few say UTF-8 and are
     * not; the declaration is checked against the bytes the way a plain-text book's would be.
     */
    internal fun markup(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, DECLARATION_BYTES), Charsets.ISO_8859_1)
        val declared = XML_ENCODING.find(head)?.groupValues?.get(1) ?: META_CHARSET.find(head)?.groupValues?.get(1)
        return TextNormalizer.decodeAsIs(bytes, declared).text
    }

    /** How far into a document its encoding is looked for; an HTML head can run long before its meta. */
    private const val DECLARATION_BYTES = 2048

    private val XML_ENCODING = Regex("""<\?xml[^>]*?\bencoding\s*=\s*["']\s*([\w.:-]+)""")
    private val META_CHARSET = Regex("""<meta\b[^>]*?\bcharset\s*=\s*["']?\s*([\w.:-]+)""", RegexOption.IGNORE_CASE)

    /**
     * Inflates the archive once, front to back, keeping only what the parser can use and only as
     * much of it as the caps allow. A ZIP learns an entry's size only by inflating it, so the caps
     * are enforced while reading rather than checked up front.
     */
    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> = ZipEntries.read(bytes) { files ->
        val entries = LinkedHashMap<String, ByteArray>()
        val buffer = ByteArray(64 * 1024)
        var inflated = 0L
        var kept = 0L
        var images = 0L
        for ((name, input) in files) {
            val extension = name.substringAfterLast('.', "").lowercase()
            val isImage = extension in imageExtensions
            var out: ByteArrayOutputStream? = if (extension in skippedExtensions) null else ByteArrayOutputStream()
            while (true) {
                val n = input.read(buffer)
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
            if (out != null) entries[name] = out.toByteArray()
        }
        entries
    }

    /** A spine document: where it lives, its markup, its text, and where its anchors land in it. */
    private data class Document(
        val path: String,
        val html: String,
        val text: String,
        val anchors: Map<String, Int>,
    )

    /** An OPF-relative href as a lookup key, keeping the anchor the fragment names. */
    private fun target(opfDir: String, href: String): String {
        val path = resolve(opfDir, href)
        val fragment = percentDecoded(href.substringAfter('#', ""))
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }

    /**
     * A document's text together with the offset each of its anchors lands at. The offsets are
     * taken by planting a marker just inside every tag that carries an id, converting as usual,
     * and then noting where the markers ended up as they are taken back out: the conversion
     * rewrites too much of the markup for a position in the source to survive it otherwise.
     * Older books mark their chapters with `<a name>` instead of an id, and those count as well.
     */
    internal fun textWithAnchors(html: String): Pair<String, Map<String, Int>> {
        val ids = ArrayList<String>()
        val planted = Regex("""<[a-zA-Z][^>]*>""").replace(html) { m ->
            val name = if (NAMED_ANCHOR.containsMatchIn(m.value)) attr(m.value, "name") else null
            val id = (attr(m.value, "id") ?: name)?.takeIf { it.isNotBlank() } ?: return@replace m.value
            ids.add(id)
            m.value + MARK + (ids.size - 1) + MARK
        }
        if (ids.isEmpty()) return htmlToText(html) to emptyMap()
        val marked = htmlToText(planted)
        val anchors = HashMap<String, Int>()
        val text = StringBuilder(marked.length)
        var i = 0
        while (i < marked.length) {
            val c = marked[i]
            if (c == MARK) {
                val close = marked.indexOf(MARK, i + 1)
                val which = if (close > i) marked.substring(i + 1, close).toIntOrNull() else null
                if (which != null && which in ids.indices) {
                    if (!anchors.containsKey(ids[which])) anchors[ids[which]] = text.length
                    i = close + 1
                    continue
                }
            }
            text.append(c)
            i++
        }
        return tidy(text.toString(), anchors)
    }

    private val NAMED_ANCHOR = Regex("""^<(?:\w+:)?a\b""", RegexOption.IGNORE_CASE)

    /** Stands in for an anchor while the markup around it is converted away. */
    private const val MARK = '\u0003'

    /**
     * [text] with its spacing settled the way [htmlToText] settles it, and [anchors] moved to
     * match. A marker is not whitespace, so one standing between two spaces, between a space and
     * a line end, or at the very start of a document kept them from being merged or trimmed while
     * the markup was converted; a document opening `<body id="top">` and an indented heading
     * started with a space. With the markers out, each run of spaces and line ends becomes one
     * space, one line end or one blank line, as it would have without them, and none at either
     * end. An anchor inside such a run lands where the text after it begins.
     */
    private fun tidy(text: String, anchors: Map<String, Int>): Pair<String, Map<String, Int>> {
        val out = StringBuilder(text.length)
        val moved = IntArray(text.length + 1)
        var i = 0
        while (i < text.length) {
            if (text[i] != ' ' && text[i] != '\n') {
                moved[i] = out.length
                out.append(text[i])
                i++
                continue
            }
            var end = i
            var breaks = 0
            while (end < text.length && (text[end] == ' ' || text[end] == '\n')) {
                if (text[end] == '\n') breaks++
                end++
            }
            if (out.isNotEmpty() && end < text.length) {
                out.append(when (breaks) { 0 -> " "; 1 -> "\n"; else -> "\n\n" })
            }
            for (k in i until end) moved[k] = out.length
            i = end
        }
        moved[text.length] = out.length
        return out.toString() to anchors.mapValues { moved[it.value.coerceIn(0, text.length)] }
    }

    /**
     * The archive paths `META-INF/encryption.xml` says are enciphered. Fonts obfuscated so they
     * cannot be lifted out of the book are listed there as well, under algorithms of their own,
     * and they leave the text as readable as ever, so they are not counted.
     */
    private fun encryptedPaths(encryption: String?): Set<String> {
        if (encryption == null) return emptySet()
        val paths = HashSet<String>()
        Regex("""<(?:\w+:)?EncryptedData\b.*?</(?:\w+:)?EncryptedData>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(encryption).forEach { m ->
                val method = Regex("""<(?:\w+:)?EncryptionMethod\b[^>]*>""").find(m.value)?.let { attr(it.value, "Algorithm") }
                if (method != null && method in FONT_OBFUSCATION) return@forEach
                val uri = Regex("""<(?:\w+:)?CipherReference\b[^>]*>""").find(m.value)?.let { attr(it.value, "URI") }
                    ?: return@forEach
                paths.add(resolve("", uri))
            }
        return paths
    }

    private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    /**
     * Whether a spine document is the printed contents rather than a chapter of the book. Such a
     * page is made almost entirely of the titles the contents document already lists, which is
     * what tells it apart from a chapter that merely opens with its own heading.
     */
    private fun isContentsPage(text: String, tocTitles: Set<String>): Boolean {
        if (tocTitles.size < MIN_LISTED_TITLES) return false
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < MIN_LISTED_TITLES) return false
        val listed = lines.count { it.lowercase() in tocTitles }
        return listed * 4 >= lines.size * 3
    }

    /** Below this many entries a contents document is too slight to recognise a page by. */
    private const val MIN_LISTED_TITLES = 3

    /** Titles and targets from an EPUB 3 nav document or an EPUB 2 NCX, in reading order. */
    private fun tocEntries(nav: String, navDir: String): List<Pair<String, String>> {
        // A navPoint holds its own label and target ahead of any navPoints nested in it, so each
        // one is read only up to where the next opens or it closes. Matching a whole navPoint to
        // its closing tag instead pairs a part's opening with its first chapter's close, and that
        // chapter goes missing from the contents.
        val ncx = NAV_POINT.findAll(nav).mapNotNull { open ->
            val from = open.range.last + 1
            val until = NAV_POINT_EDGE.find(nav, from)?.range?.first ?: nav.length
            val own = nav.substring(from, until)
            val label = NAV_LABEL.find(own)?.groupValues?.get(1) ?: return@mapNotNull null
            val src = NAV_CONTENT.find(own)?.let { attr(it.value, "src") }?.trim() ?: return@mapNotNull null
            labelText(label) to target(navDir, src)
        }.filter { it.first.isNotBlank() }.toList()
        if (ncx.isNotEmpty()) return ncx
        // An EPUB 3 nav document may also carry landmarks and a page list; only the toc is contents.
        val tocNav = Regex("""<(?:\w+:)?nav\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(nav)
            .firstOrNull { tag -> attr(tag.value, "epub:type")?.split(' ')?.contains("toc") == true }
        val toc = tocNav?.let { open ->
            val close = nav.indexOf("</nav", open.range.last, ignoreCase = true).takeIf { it >= 0 } ?: nav.length
            nav.substring(open.range.last + 1, close)
        } ?: nav
        return Regex("""<(?:\w+:)?a\b([^>]*)>(.*?)</(?:\w+:)?a\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(toc)
            .mapNotNull { m -> attr(m.groupValues[1], "href")?.trim()?.let { htmlToText(m.groupValues[2]).trim().take(80) to target(navDir, it) } }
            .filter { it.first.isNotBlank() }
            .toList()
    }

    /** An NCX label: entities decoded and the lines a pretty-printer broke it into joined back up. */
    private fun labelText(raw: String): String {
        val text = raw.replace(Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL), "$1")
        return decodeEntities(text).replace(Regex("\\s+"), " ").trim().take(80)
    }

    private val NAV_POINT = Regex("""<(?:\w+:)?navPoint\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val NAV_POINT_EDGE = Regex("""<(?:\w+:)?navPoint\b|</(?:\w+:)?navPoint\s*>""", RegexOption.IGNORE_CASE)
    private val NAV_LABEL = Regex("""<(?:\w+:)?text\b[^>]*>(.*?)</(?:\w+:)?text\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val NAV_CONTENT = Regex("""<(?:\w+:)?content\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * An attribute's value. The name must stand on its own: `id` is not the tail of `data-id`
     * or `xml:id`, which an anchor would otherwise be placed by when it comes first in the tag.
     * HTML written before XHTML spells names in capitals and leaves values unquoted, as in
     * `<A NAME=c3>`, and those are read too.
     */
    internal fun attr(tag: String, name: String): String? {
        val pattern = attributePatterns.getOrPut(name) {
            Regex("""(?<![\w:.-])$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)
        }
        val match = pattern.find(tag) ?: return null
        val (double, single, bare) = match.destructured
        return double.ifEmpty { single.ifEmpty { bare } }
    }

    private val attributePatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * Joins an OPF-relative href to the OPF directory, decoding %20 and collapsing "../". An href
     * that starts at the root of the archive is taken from there, and Windows backslashes are
     * read as the slashes they stand for.
     */
    internal fun resolve(opfDir: String, href: String): String {
        val decoded = percentDecoded(href.substringBefore('#')).replace('\\', '/')
        return normalise(if (opfDir.isEmpty() || decoded.startsWith("/")) decoded else "$opfDir/$decoded")
    }

    /** URLDecoder is for form encoding, where + is a space; in a plain URI it is a plus sign. */
    private fun percentDecoded(href: String): String =
        runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)

    /** A path with its empty and "." parts dropped and each ".." taking the part before it away. */
    private fun normalise(raw: String): String {
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
        s = s.replace(Regex("<((?:\\w+:)?(?:style|script|head))\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        // Ruby annotations are the small readings printed above a word. Set inline they follow
        // every annotated word in full, so that 漢字 with its reading comes out as 漢字(かんじ).
        s = s.replace(Regex("<(rt|rp|rtc)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        // Mark the breaks the markup asks for, so the ones the source file merely wrapped at can
        // be flattened away: a paragraph should reach the reader as one long line, not as the
        // typesetting of whoever produced the file.
        s = s.replace(Regex("<(?:\\w+:)?br\\b[^>]*/?>", RegexOption.IGNORE_CASE), BREAK)
        s = s.replace(Regex("</(?:\\w+:)?(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|dd|dt|pre|table)\\s*>", RegexOption.IGNORE_CASE), PARAGRAPH)
        s = s.replace(Regex("<(?:\\w+:)?hr\\b[^>]*/?>", RegexOption.IGNORE_CASE), PARAGRAPH)
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s)
        s = s.replace(' ', ' ')
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(PARAGRAPH, "\n\n").replace(BREAK, "\n")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** Stand in for the breaks the markup asked for while ordinary whitespace is collapsed. */
    private const val BREAK = "\u0001"
    private const val PARAGRAPH = "\u0002"

    /**
     * The named character references of HTML 4, which XHTML written by hand or exported from a
     * word processor uses freely without the DTD that would declare them, plus XML's own. A soft
     * hyphen is dropped and the narrow spaces become plain ones, as a reader shows them.
     */
    private val namedEntities: Map<String, String> = HashMap<String, String>().apply {
        val latin1 = "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn " +
            "sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute " +
            "Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde " +
            "Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave " +
            "aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth " +
            "ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        for ((i, name) in latin1.split(' ').withIndex()) put(name, (0xA0 + i).toChar().toString())
        val greek = "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho " +
            "_ Sigma Tau Upsilon Phi Chi Psi Omega"
        for ((i, name) in greek.split(' ').withIndex()) {
            if (name == "_") continue
            put(name, (0x391 + i).toChar().toString())
            put(name.lowercase(), (0x3B1 + i).toChar().toString())
        }
        put("sigmaf", "ς")
        val others = "quot 34 amp 38 apos 39 lt 60 gt 62 OElig 338 oelig 339 Scaron 352 scaron 353 Yuml 376 " +
            "fnof 402 circ 710 tilde 732 thetasym 977 upsih 978 piv 982 ensp 8194 emsp 8195 thinsp 8201 " +
            "zwnj 8204 zwj 8205 lrm 8206 rlm 8207 ndash 8211 mdash 8212 lsquo 8216 rsquo 8217 sbquo 8218 " +
            "ldquo 8220 rdquo 8221 bdquo 8222 dagger 8224 Dagger 8225 bull 8226 hellip 8230 permil 8240 " +
            "prime 8242 Prime 8243 lsaquo 8249 rsaquo 8250 oline 8254 frasl 8260 euro 8364 image 8465 " +
            "weierp 8472 real 8476 trade 8482 alefsym 8501 larr 8592 uarr 8593 rarr 8594 darr 8595 harr 8596 " +
            "crarr 8629 lArr 8656 uArr 8657 rArr 8658 dArr 8659 hArr 8660 forall 8704 part 8706 exist 8707 " +
            "empty 8709 nabla 8711 isin 8712 notin 8713 ni 8715 prod 8719 sum 8721 minus 8722 lowast 8727 " +
            "radic 8730 prop 8733 infin 8734 ang 8736 and 8743 or 8744 cap 8745 cup 8746 int 8747 there4 8756 " +
            "sim 8764 cong 8773 asymp 8776 ne 8800 equiv 8801 le 8804 ge 8805 sub 8834 sup 8835 nsub 8836 " +
            "sube 8838 supe 8839 oplus 8853 otimes 8855 perp 8869 sdot 8901 lceil 8968 rceil 8969 " +
            "lfloor 8970 rfloor 8971 lang 9001 rang 9002 loz 9674 spades 9824 clubs 9827 hearts 9829 diams 9830"
        others.split(' ').chunked(2).forEach { (name, code) -> put(name, String(Character.toChars(code.toInt()))) }
        put("shy", "")
        for (space in listOf("ensp", "emsp", "thinsp")) put(space, " ")
    }

    internal fun decodeEntities(input: String): String =
        Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z][a-zA-Z0-9]*);").replace(input) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16))
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
                else -> namedEntities[body] ?: m.value
            }
        }

    /**
     * The character a numeric reference names. References to 128 to 159 name control codes,
     * but a page saved from Windows meant the curly quotes and dashes Windows-1252 keeps there,
     * and browsers have always shown them so.
     */
    private fun codePoint(cp: Int?): String = when {
        cp == null || cp <= 0 || cp > 0x10FFFF -> ""
        cp in 0x80..0x9F -> String(byteArrayOf(cp.toByte()), WINDOWS_1252).replace("�", "")
        else -> String(Character.toChars(cp))
    }

    private val WINDOWS_1252 = charset("windows-1252")
}
