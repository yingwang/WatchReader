package com.watchreader.shared

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Books deleted on the phone while the watch was out of reach. A message to a watch that is not
 * there is simply lost, and the watch kept its copy although the phone had said it would go. A
 * data item waits in Play Services until the watch comes back, and the watch deletes the book when
 * it sees one; its answer (a book-removed message) lets the phone drop the item again.
 */
object PendingDeletes {
    const val PREFIX = "/pending-delete/"

    suspend fun publish(context: Context, bookId: String) {
        val request = PutDataRequest.create(PREFIX + Uri.encode(bookId))
            .setData(bookId.toByteArray(Charsets.UTF_8))
            .setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /** The phone drops its note once the watch has answered; only the node that wrote it does this. */
    suspend fun clear(context: Context, bookId: String) {
        val node = Wearable.getNodeClient(context).localNode.await()
        val uri = Uri.Builder().scheme("wear").authority(node.id).encodedPath(PREFIX + Uri.encode(bookId)).build()
        Wearable.getDataClient(context).deleteDataItems(uri).await()
    }

    fun bookId(path: String?, bytes: ByteArray?): String? {
        if (path?.startsWith(PREFIX) != true || bytes == null) return null
        return bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /** Every book still waiting to be deleted; the watch runs this on start as well. */
    suspend fun all(context: Context): List<String> {
        val items = Wearable.getDataClient(context).dataItems.await()
        return try {
            items.mapNotNull { bookId(it.uri.path, it.data) }
        } finally {
            items.release()
        }
    }
}
