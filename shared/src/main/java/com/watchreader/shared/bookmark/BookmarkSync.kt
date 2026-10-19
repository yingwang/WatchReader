package com.watchreader.shared.bookmark

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Both nodes own DataItems. A single bookmark per item avoids the 100 KB per-item limit. */
object BookmarkSync {
    const val PREFIX = "/reader-bookmarks/v1/"
    private val writes = Mutex()

    fun path(bookmark: Bookmark): String = PREFIX + bookmark.key

    fun decode(path: String?, bytes: ByteArray?): Bookmark? {
        if (path?.startsWith(PREFIX) != true || bytes == null || bytes.size > 8192) return null
        return runCatching { BookmarkCodec.decode(bytes.toString(Charsets.UTF_8)) }
            .getOrNull()?.takeIf { path(it) == path }
    }

    /** Call for TYPE_CHANGED only. TYPE_DELETED is not a bookmark removal; tombstones are. */
    suspend fun receive(context: Context, path: String?, bytes: ByteArray?): Boolean {
        val record = decode(path, bytes) ?: return false
        BookmarkRepository.get(context).store.merge(record)
        return true
    }

    /** Safe before the book arrives: records are keyed by its transfer ID, not a database row. */
    suspend fun restore(context: Context) {
        val items = Wearable.getDataClient(context).dataItems.await()
        try {
            for (item in items) receive(context, item.uri.path, item.data)
        } finally {
            items.release()
        }
    }

    suspend fun flush(context: Context) = writes.withLock {
        val client = Wearable.getDataClient(context)
        for (record in BookmarkRepository.get(context).store.locallyAuthored()) {
            val request = PutDataRequest.create(path(record))
                .setData(BookmarkCodec.encode(record).toByteArray(Charsets.UTF_8)).setUrgent()
            client.putDataItem(request).await()
        }
    }
}
