package com.watchreader.mobile.data.model

/** A public-domain book from Project Gutenberg's catalogue, as the free books screen lists it. */
data class FreeBook(
    /** Gutenberg's ebook number. */
    val id: Int,
    val title: String,
    /** Names as a reader writes them, "Jane Austen" rather than the catalogue's "Austen, Jane". */
    val authors: List<String>,
    /** Two-letter codes, as Gutenberg tags its books. */
    val languages: List<String>,
    val subjects: List<String>,
    val downloadCount: Int,
    val coverUrl: String?,
    /** Where the book can be fetched, best first; never empty for a book that is listed. */
    val files: List<FreeBookFile>,
)

data class FreeBookFile(val url: String, val isEpub: Boolean)

/** One page of a listing; [next] is the address of the page after it, or null at the end. */
data class FreeBookPage(val books: List<FreeBook>, val next: String?, val total: Int)
