package com.watchreader.wear.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.watchreader.wear.data.repository.WearBookRepository
import com.watchreader.wear.ui.navigation.WearNavigation
import com.watchreader.wear.ui.theme.WatchReaderWearTheme
import com.watchreader.wear.ui.viewmodel.continueReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class WearActivity : ComponentActivity() {
    /** Book to open straight away, e.g. from the read-aloud notification; consumed by navigation. */
    private var openRequest by mutableStateOf<OpenRequest?>(null)

    /** Where the app opens; null only while the book to go back to is still being looked up. */
    private var start by mutableStateOf<Start?>(Start(resumeBookId = null))

    class OpenRequest(val bookId: String, val serial: Long = System.nanoTime())

    /** [resumeBookId] opens that book's page with the library behind it; null opens the library. */
    private class Start(val resumeBookId: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch screen (the app icon on black) has to be in place before the activity is
        // created; it then hands over to the app's own theme.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val bookId = intent?.getStringExtra(EXTRA_BOOK_ID)
        bookId?.let { openRequest = OpenRequest(it) }
        // Opening the app from the launcher goes back into the book being read, as if "Continue
        // reading" had been tapped, so Back still finds the library. Anything else (a notification
        // naming a book, the activity coming back from saved state) opens where it always did.
        val plainLaunch = isPlainLaunch(savedInstanceState != null, intent?.action, intent?.categories, bookId)
        if (plainLaunch) {
            start = null
            lifecycleScope.launch {
                start = Start(withTimeoutOrNull(RESUME_LOOKUP_MS) { bookToResume() })
            }
        }
        setContent {
            WatchReaderWearTheme {
                start?.let { WearNavigation(openRequest = openRequest, resumeBookId = it.resumeBookId) }
            }
        }
        // Keep the launch screen up until the app knows where it opens, so a start on a book goes
        // from the icon straight to its page, without an empty frame or a glimpse of the library
        // on the way. The lookup gives up after RESUME_LOOKUP_MS, so the hold is short; any
        // other start already knows where it opens and lets the launch screen go at once.
        splash.setKeepOnScreenCondition { start == null }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_BOOK_ID)?.let { openRequest = OpenRequest(it) }
    }

    /** The "Continue reading" book, as long as its text is still on the watch to be opened. */
    private suspend fun bookToResume(): String? = withContext(Dispatchers.IO) {
        val book = continueReading(WearBookRepository.observeAll().first()) ?: return@withContext null
        book.id.takeIf { File(book.filePath).isFile }
    }

    companion object {
        const val EXTRA_BOOK_ID = "open_book_id"
        private const val RESUME_LOOKUP_MS = 1500L
    }
}

/**
 * Whether the activity is being started afresh from the launcher: not brought back from saved
 * state, and not sent to a particular book by the read-aloud notification or anything else.
 */
internal fun isPlainLaunch(restoring: Boolean, action: String?, categories: Set<String>?, bookId: String?): Boolean =
    !restoring && bookId == null && action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_LAUNCHER) == true
