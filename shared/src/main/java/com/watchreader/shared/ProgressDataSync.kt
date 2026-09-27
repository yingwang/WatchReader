package com.watchreader.shared

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Each node owns its latest progress per book. Play Services persists it while disconnected. */
object ProgressDataSync {
    const val PREFIX = "/reading-progress/"
    private val writes = Mutex()

    suspend fun publish(context: Context, progress: ReadingProgress) = writes.withLock {
        val client = Wearable.getDataClient(context)
        val node = Wearable.getNodeClient(context).localNode.await()
        val path = PREFIX + Uri.encode(progress.bookId)
        val uri = Uri.Builder().scheme("wear").authority(node.id).encodedPath(path).build()
        val previous = decode(path, client.getDataItem(uri).await()?.data)
        // An older coroutine finishing last must not replace the offline copy of a newer read.
        if (!shouldPublish(previous, progress)) return@withLock
        val request = PutDataRequest.create(path)
            .setData(progress.toJson().toByteArray(Charsets.UTF_8))
            .setUrgent()
        client.putDataItem(request).await()
    }

    internal fun shouldPublish(previous: ReadingProgress?, next: ReadingProgress): Boolean =
        previous == null || next.lastReadEpochMs > previous.lastReadEpochMs ||
            (next.lastReadEpochMs == previous.lastReadEpochMs && next != previous)

    fun decode(path: String?, bytes: ByteArray?): ReadingProgress? {
        if (path?.startsWith(PREFIX) != true || bytes == null) return null
        return runCatching { ReadingProgress.fromJson(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }

    /** Also used after book import: its progress may have arrived before the book itself. */
    suspend fun restore(context: Context, apply: suspend (ReadingProgress) -> Unit) {
        val items = Wearable.getDataClient(context).dataItems.await()
        try {
            for (item in items) decode(item.uri.path, item.data)?.let { apply(it) }
        } finally {
            items.release()
        }
    }
}
