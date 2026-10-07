package com.watchreader.mobile.data.model

/** A book in Project Gutenberg's catalogue, as a list of search results or popular books gives it. */
data class FreeBook(
    /** Gutenberg's ebook number. */
    val id: Int,
    val title: String,
    /** As Gutenberg writes it for display, "Jane Austen"; null when the catalogue names none. */
    val author: String?,
)

/** One page of a list; [next] is the address of the page after it, or null at the end. */
data class FreeBookPage(val books: List<FreeBook>, val next: String?)

/** What a book's own catalogue entry adds to the list's title and author, fetched when it is opened. */
data class FreeBookDetails(
    /** Two-letter codes, as Gutenberg tags its books. */
    val languages: List<String>,
    val subjects: List<String>,
    /** Gutenberg says so in so many words; a book still in copyright is not offered. */
    val publicDomain: Boolean,
    /** Where the book can be fetched, best first; empty for a recording or anything else not to be read. */
    val files: List<FreeBookFile>,
)

data class FreeBookFile(val url: String, val isEpub: Boolean)
