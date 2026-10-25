package com.watchreader.shared

import kotlinx.coroutines.CancellationException

/**
 * How the Data Layer listener services on both devices take what they are handed. Their callbacks
 * run on a thread of Play services, where anything thrown ends the process: a bookmark file the
 * app refuses to read, a full disk or a full database would crash it, and crash it again with
 * every event like it that came after, while the events behind the bad one in the same batch
 * were never applied. Each event and each message is handled on its own instead. A failure is
 * reported and the rest carry on; a cancellation is not a failure, and goes through.
 */
object ListenerGuard {
    /** Runs [block]; an exception other than a cancellation goes to [onFailure] instead of up. */
    inline fun guard(onFailure: (Exception) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    /** Hands each of [items] to [handle] in turn; one that fails goes to [onFailure], and the next is handled all the same. */
    inline fun <T> each(items: Iterable<T>, onFailure: (T, Exception) -> Unit, handle: (T) -> Unit) {
        for (item in items) guard({ onFailure(item, it) }) { handle(item) }
    }
}
