package com.watchreader.mobile.data.repository

import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.FreeBookDetails
import com.watchreader.mobile.data.model.FreeBookFile
import com.watchreader.mobile.data.model.FreeBookPage
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Project Gutenberg's own catalogue feeds (OPDS, which is Atom): the addresses asked for and what
 * is read from the answers. Nothing here touches the network, so all of it is tested on the JVM.
 */
internal object GutenbergCatalog {
    private const val SITE = "https://www.gutenberg.org"
    const val SEARCH = "$SITE/ebooks/search.opds/"

    /**
     * Languages offered as a filter: those with more than a handful of books on Gutenberg, and a
     * few more that many phones are set to. A phone set to another language starts on all of them.
     */
    val LANGUAGES = listOf(
        "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "fi", "fr", "hu",
        "it", "ja", "la", "nl", "no", "pl", "pt", "ru", "sv", "tl", "zh",
    )

    /** The filter a phone in [locale] starts on: its own language when Gutenberg has it, else null for all. */
    fun languageFor(locale: Locale): String? {
        val code = when (val language = locale.language) {
            // Gutenberg tags Norwegian without telling Bokmål from Nynorsk, and Filipino as Tagalog.
            "nb", "nn" -> "no"
            "fil" -> "tl"
            else -> language
        }
        return code.takeIf { it in LANGUAGES }
    }

    /**
     * The first page of the most downloaded books, or of those matching [search], in [language]
     * or in every language when it is null. The language is a word of Gutenberg's own query
     * language, "l.sv", so a search and a filter go in the same query.
     */
    fun searchUrl(search: String, language: String?): String {
        val words = search.trim().replace(WHITESPACE, " ")
        val query = listOfNotNull(words.ifEmpty { null }, language?.let { "l.$it" }).joinToString(" ")
        return if (query.isEmpty()) "$SEARCH?sort_order=downloads"
        else "$SEARCH?query=${URLEncoder.encode(query, "UTF-8")}&sort_order=downloads"
    }

    /** A book's own feed, with its language, subjects, rights and files. */
    fun bookUrl(id: Int) = "$SITE/ebooks/$id.opds"

    /** Gutenberg's cover for every book, made up from the title where the book has none of its own. */
    fun coverUrl(id: Int, small: Boolean) = "$SITE/cache/epub/$id/pg$id.cover.${if (small) "small" else "medium"}.jpg"

    /**
     * The plain text, asked for where it is kept: Gutenberg sends its own .txt.utf-8 link on to a
     * plain http:// address, which Android refuses.
     */
    fun textUrl(id: Int) = "$SITE/cache/epub/$id/pg$id.txt"

    /**
     * A page of search results. A search for a name also lists the authors and subjects that
     * match, and an empty one says "No records found."; neither is a book, and both are left out.
     */
    fun parseSearch(xml: String): FreeBookPage {
        val feed = parse(xml)
        val books = feed.children("entry").mapNotNull { entry ->
            val id = BOOK_ID.matchEntire(entry.text("id").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val title = cleanTitle(entry.text("title").orEmpty()).ifEmpty { return@mapNotNull null }
            // A book with no author has its download count here instead.
            val author = entry.text("content")?.replace(WHITESPACE, " ")?.takeUnless { DOWNLOADS.matches(it) }
            FreeBook(id, title, author?.ifEmpty { null })
        }
        // Only Gutenberg's own search pages are followed, and only over https.
        val next = feed.children("link").firstOrNull { it.getAttribute("rel") == "next" }
            ?.let { absolute(it.getAttribute("href")) }
            ?.takeIf { it.startsWith(SEARCH) }
        return FreeBookPage(books, next)
    }

    /**
     * A book's own feed. It has an entry for each edition, the one without images first; their
     * epubs are offered in that order, then the plain text. An epub the feed says is larger than
     * an import takes is not fetched only to be refused, and a recording, or anything else that
     * is not text, has no files at all. Null when the feed has no entry.
     */
    fun parseBook(xml: String, id: Int): FreeBookDetails? {
        val entries = parse(xml).children("entry")
        val first = entries.firstOrNull() ?: return null
        val categories = entries.flatMap { it.children("category") }
        val kind = categories.firstOrNull { it.getAttribute("scheme").endsWith("/DCMIType") }?.getAttribute("term")
        val epubs = entries.flatMap { it.children("link") }
            .filter { it.getAttribute("rel") == ACQUISITION && it.getAttribute("type").startsWith("application/epub+zip") }
            .filter { (it.getAttribute("length").toLongOrNull() ?: 0L) <= BookRepository.MAX_BOOK_BYTES }
            .mapNotNull { absolute(it.getAttribute("href")) }
            .distinct()
        val (plain, illustrated) = epubs.partition { it.endsWith(".epub.noimages") }
        val files = if (kind != null && kind != "Text") emptyList()
        else plain.take(1).map { FreeBookFile(it, isEpub = true) } +
            illustrated.take(1).map { FreeBookFile(it, isEpub = true) } +
            FreeBookFile(textUrl(id), isEpub = false)
        return FreeBookDetails(
            languages = entries.flatMap { it.children("language") }.map { it.textContent.trim() }.filter { it.isNotEmpty() }.distinct(),
            subjects = first.children("category")
                .filter { it.getAttribute("scheme").endsWith("/LCSH") }
                .map { it.getAttribute("term").trim() }
                .filter { it.isNotEmpty() },
            publicDomain = entries.mapNotNull { it.text("rights") }.firstOrNull()
                ?.contains("public domain in the usa", ignoreCase = true) == true,
            files = files,
        )
    }

    /**
     * A catalogue title as a reader would write it. Search results end a title with its language
     * in brackets, "Sult (Norwegian)", sometimes two of them; a subtitle may follow a line break
     * or the " :  " a library catalogue joins it with.
     */
    fun cleanTitle(title: String): String {
        var text = title.trim()
        while (true) {
            val last = TRAILING_BRACKETS.find(text) ?: break
            if (!isLanguageName(last.groupValues[1])) break
            text = text.substring(0, last.range.first).trimEnd()
        }
        return text.replace(SUBTITLE_JOIN, ": ")
            .replace(LINE_BREAK) { m -> m.groupValues[1].ifEmpty { ":" } + " " }
            .replace(WHITESPACE, " ")
            .trim()
            .removeSuffix(":")
            .trim()
    }

    /** "Swedish", and Gutenberg's longer names such as "Modern Greek (1453-)" or "Greek, Ancient (to 1453)". */
    private fun isLanguageName(name: String): Boolean =
        name.substringBefore(" (").substringBefore(",").trim() in LANGUAGE_NAMES

    private val LANGUAGE_NAMES: Set<String> by lazy {
        Locale.getISOLanguages().map { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }.toSet() +
            setOf("Modern Greek", "Ancient Greek", "Old English", "Middle English")
    }

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // A feed has no document type, and none is fetched from anywhere; Android's parser
            // does not know the switch and never fetches one either.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    /** [href] made absolute against the site, over https; null when it is no address at all. */
    private fun absolute(href: String): String? =
        runCatching { BookRepository.secureAddress(URL(URL("$SITE/"), href.trim()).toString()) }.getOrNull()

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { (it.localName ?: it.nodeName) == name }

    private fun Element.text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()

    private const val ACQUISITION = "http://opds-spec.org/acquisition"
    private val BOOK_ID = Regex("""https?://www\.gutenberg\.org/ebooks/(\d+)\.opds""")
    private val DOWNLOADS = Regex("""\d+ downloads?""")
    private val WHITESPACE = Regex("""\s+""")
    /** The last bracketed part of a title, which may hold one bracket of its own. */
    private val TRAILING_BRACKETS = Regex("""\s*\(([^()]*(?:\([^()]*\))?[^()]*)\)\s*$""")
    private val SUBTITLE_JOIN = Regex("""\s+:\s{2,}""")
    /** A line break, the spaces around it and any punctuation the line before it ends with. */
    private val LINE_BREAK = Regex("""[ \t]*([:;,.]?)[ \t]*[\r\n]+\s*""")
}
