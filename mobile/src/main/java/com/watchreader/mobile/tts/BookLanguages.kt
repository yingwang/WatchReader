package com.watchreader.mobile.tts

import com.watchreader.mobile.data.model.Book
import java.util.Locale

/**
 * The languages a book is read aloud in, for fetching their voices ahead of time and for the voice
 * choices in the reader.
 *
 * The whole book has to be read through to know, so each edition of a book is read once and the
 * answer kept while the app runs; starting to listen again does not read the book again.
 */
object BookLanguages {
    private val known = HashMap<String, Set<Locale>>()

    /** The languages [book], whose text is [text], is read aloud in. Call off the main thread. */
    fun of(book: Book, text: String): Set<Locale> {
        val key = edition(book)
        synchronized(known) { known[key] }?.let { return it }
        // The phone's own language helps tell a Latin-script book's language (see LanguageDetector).
        val languages = LanguageDetector.languagesIn(text, Locale.getDefault())
        synchronized(known) { known[key] = languages }
        return languages
    }

    // A book imported again is a new row with a new size or a new arrival time.
    private fun edition(book: Book) = "${book.id}:${book.sizeBytes}:${book.addedEpochMs}"
}
