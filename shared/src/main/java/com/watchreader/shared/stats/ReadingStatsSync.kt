package com.watchreader.shared.stats

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * The watch's reading time per book, kept as a data item so it reaches the phone after time
 * apart, the way reading progress does. Only the watch writes these; the phone only reads them.
 */
object ReadingStatsSync {
    const val PREFIX = "/reading-time/"

    suspend fun publish(context: Context, report: ReadingReport) {
        val request = PutDataRequest.create(PREFIX + Uri.encode(report.bookId))
            .setData(report.toJson().toByteArray(Charsets.UTF_8))
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    suspend fun forget(context: Context, bookId: String) {
        val node = Wearable.getNodeClient(context).localNode.await()
        val uri = Uri.Builder().scheme("wear").authority(node.id).encodedPath(PREFIX + Uri.encode(bookId)).build()
        Wearable.getDataClient(context).deleteDataItems(uri).await()
    }

    fun decode(path: String?, bytes: ByteArray?): ReadingReport? {
        if (path?.startsWith(PREFIX) != true || bytes == null) return null
        return runCatching { ReadingReport.fromJson(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }

    suspend fun restore(context: Context, apply: suspend (ReadingReport) -> Unit) {
        val items = Wearable.getDataClient(context).dataItems.await()
        try {
            for (item in items) decode(item.uri.path, item.data)?.let { apply(it) }
        } finally {
            items.release()
        }
    }
}
