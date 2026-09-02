package com.watchreader.mobile.ui

import android.content.Intent
import android.net.Uri

/**
 * Holds a file handed to us by another app ("Share to WatchReader" / "Open with") until the
 * add-book screen consumes it. Navigation arguments cannot carry a content Uri cleanly.
 */
object SharedIntent {
    @Volatile
    var pendingUri: Uri? = null
        private set

    /** Records the file in [intent], if it carries one. Returns true when something was taken. */
    fun capture(intent: Intent?): Boolean {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) pendingUri = uri
        return uri != null
    }

    fun consume(): Uri? = pendingUri.also { pendingUri = null }
}
