package com.watchreader.mobile.ui

import android.content.Intent
import android.net.Uri

/**
 * Holds what another app handed us ("Share to WatchReader" / "Open with") until the add-book
 * screen consumes it. Navigation arguments cannot carry a content Uri cleanly.
 */
object SharedIntent {
    /** One thing shared in, as the add-book screen takes it. */
    sealed class Shared {
        /** A file, which the screen selects. */
        data class File(val uri: Uri) : Shared()

        /** A link to a book, which the screen puts in its link field. */
        data class Link(val url: String) : Shared()

        /**
         * Text with no link in it. A browser that shares selected text lists every app taking
         * text/plain, WatchReader among them, and a share that did nothing at all would look broken.
         */
        object Unusable : Shared()
    }

    @Volatile
    var pending: Shared? = null
        private set

    /** Records what [intent] carries, if anything. Returns true when something was taken. */
    fun capture(intent: Intent?): Boolean {
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                when {
                    stream != null -> Shared.File(stream)
                    else -> link(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { Shared.Link(it) } ?: Shared.Unusable
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { Shared.File(it) }
            else -> null
        }
        if (shared != null) pending = shared
        return shared != null
    }

    fun consume(): Shared? = pending.also { pending = null }

    /**
     * The first http or https link in shared text. A browser shares a page's address on its own
     * or after the page's title, and a link set in a sentence keeps the punctuation that closes
     * the sentence out of it.
     */
    internal fun link(text: String?): String? {
        var found = LINK.find(text ?: return null)?.value ?: return null
        while (found.isNotEmpty()) {
            val last = found.last()
            // A closing bracket belongs to the link only when the link opened it, as a wiki's do.
            val unopened = last == ')' && found.count { it == ')' } > found.count { it == '(' }
            if (last in SENTENCE_PUNCTUATION || unopened) found = found.dropLast(1) else break
        }
        return found.takeIf { it.substringAfter("://").isNotEmpty() }
    }

    private val LINK = Regex("""https?://[^\s<>"'\u201C\u201D]+""", RegexOption.IGNORE_CASE)

    private const val SENTENCE_PUNCTUATION = ".,;:!?]}\u2019"
}
