package com.watchreader.wear.tts

import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The languages the books on this watch are read aloud in, for the voices list in Settings and for
 * fetching the voices ahead of time.
 *
 * Every book has to be read through to know, so each edition of a book is read once and the answer
 * kept while the app runs; going back to the page does not read the whole library again. A book
 * whose text cannot be read adds nothing, and is tried again next time.
 */
object BookLanguages {
    private val known = HashMap<String, Set<Locale>>()

    suspend fun needed(): Set<Locale> = withContext(Dispatchers.IO) {
        val needed = HashSet<Locale>()
        for (book in WearBookRepository.observeAll().first()) {
            val languages = synchronized(known) { known[edition(book)] }
                ?: runCatching { of(book, WearBookRepository.loadText(book)) }.getOrNull()
            if (languages != null) needed += languages
        }
        needed
    }

    /** The languages [book], whose text is [text], is read aloud in. Call off the main thread. */
    fun of(book: WearBook, text: String): Set<Locale> {
        val key = edition(book)
        synchronized(known) { known[key] }?.let { return it }
        // The watch's own language helps tell a Latin-script book's language (see LanguageDetector).
        val languages = LanguageDetector.languagesIn(text, Locale.getDefault())
        synchronized(known) { known[key] = languages }
        return languages
    }

    // A book sent again comes with a new size or a new arrival time.
    private fun edition(book: WearBook) = "${book.id}:${book.sizeBytes}:${book.addedEpochMs}"
}
