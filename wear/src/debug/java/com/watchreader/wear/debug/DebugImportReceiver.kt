package com.watchreader.wear.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.watchreader.shared.TextNormalizer
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/**
 * adb shell am broadcast -a com.watchreader.debug.IMPORT --es path /data/local/tmp/book.txt --es title 书名
 */
class DebugImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val title = intent.getStringExtra("title") ?: File(path).nameWithoutExtension
        val bytes = runCatching { File(path).readBytes() }.getOrElse {
            Log.e("WatchReader", "debug import: cannot read $path", it)
            return
        }
        val text = TextNormalizer.decode(bytes).text
        val id = UUID.randomUUID().toString()
        val file = File(WearBookRepository.getBooksDir(), "$id.txt")
        file.writeText(text, Charsets.UTF_8)
        runBlocking {
            WearBookRepository.insert(
                WearBook(id = id, title = title, filePath = file.absolutePath, sizeBytes = file.length(),
                    addedEpochMs = System.currentTimeMillis(), totalChars = text.length),
            )
        }
        Log.i("WatchReader", "debug import: '$title' ${text.length} chars")
    }
}
