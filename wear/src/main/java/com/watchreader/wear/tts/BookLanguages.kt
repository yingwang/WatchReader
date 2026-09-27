package com.watchreader.wear.tts

import com.watchreader.wear.data.repository.WearBookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The languages the books on this watch are read aloud in, for the voices list in Settings.
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
            // A book sent again comes with a new size or a new arrival time.
            val edition = "${book.id}:${book.sizeBytes}:${book.addedEpochMs}"
            val languages = synchronized(known) { known[edition] }
                ?: runCatching { LanguageDetector.languagesIn(WearBookRepository.loadText(book)) }.getOrNull()
                    ?.also { synchronized(known) { known[edition] = it } }
            if (languages != null) needed += languages
        }
        needed
    }
}
