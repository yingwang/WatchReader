package com.watchreader.mobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.shared.ReadingProgress
import kotlinx.coroutines.runBlocking

/**
 * Pretends the watch read a book to a place, stamped now, as its progress report would:
 * adb shell am broadcast -a com.watchreader.debug.PROGRESS -n com.watchreader/.mobile.debug.DebugProgressReceiver --es book <id> --ei offset 12345
 */
class DebugProgressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getStringExtra("book") ?: return
        val offset = intent.getIntExtra("offset", -1).takeIf { it >= 0 } ?: return
        runBlocking {
            val book = BookRepository.getById(bookId) ?: return@runBlocking
            val total = book.totalChars.coerceAtLeast(1)
            BookRepository.applyProgress(
                ReadingProgress(bookId, offset.coerceAtMost(total), offset.toFloat() / total, System.currentTimeMillis()),
            )
            Log.i("WatchReader", "debug progress: $bookId at $offset")
        }
    }
}
