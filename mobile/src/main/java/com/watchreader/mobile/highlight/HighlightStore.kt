package com.watchreader.mobile.highlight

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One book's highlights, as the reader sees them. Every change is on disk before it shows: a
 * failed write throws and leaves both the file and [items] as they were. A store whose file could
 * not be read refuses every change, so a damaged file is kept for what it holds instead of being
 * overwritten with whatever was made since.
 */
class HighlightStore(
    private val storage: HighlightStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    private var loaded: List<Highlight>? = null
    private var forgotten = false
    private val mutableItems = MutableStateFlow<List<Highlight>>(emptyList())

    /** In book order. */
    val items: StateFlow<List<Highlight>> = mutableItems.asStateFlow()

    /** Reads the file once; later calls return at once. Throws when it cannot be read. */
    suspend fun load() = lock.withLock { current(); Unit }

    /**
     * Marks [start, end) with [quote]. Marking exactly the words of an existing highlight again
     * changes that one rather than laying a second on top of it.
     */
    suspend fun add(start: Int, end: Int, quote: String, color: HighlightColor, note: String = ""): Highlight = lock.withLock {
        val items = current()
        val at = now()
        val same = items.firstOrNull { it.start == start && it.end == end }
        val record = if (same != null) {
            same.copy(color = color, note = note.ifBlank { same.note }.trim(), updatedAt = at)
        } else {
            Highlight(newId(), start, end, quote, color, note.trim(), createdAt = at, updatedAt = at)
        }
        save(items.filterNot { it.id == record.id } + record)
        record
    }

    suspend fun recolor(id: String, color: HighlightColor): Highlight? = change(id) { it.copy(color = color) }

    /** A blank note takes the note away and leaves the highlight. */
    suspend fun annotate(id: String, note: String): Highlight? = change(id) { it.copy(note = note.trim()) }

    /** The highlight taken away, to be put back with [restore], or null when there was none. */
    suspend fun delete(id: String): Highlight? = lock.withLock {
        val items = current()
        val gone = items.firstOrNull { it.id == id } ?: return@withLock null
        save(items - gone)
        gone
    }

    /** Puts back a highlight just deleted, as it was. */
    suspend fun restore(highlight: Highlight) = lock.withLock {
        val items = current()
        save(items.filterNot { it.id == highlight.id } + highlight)
    }

    /**
     * The book has gone from the library, and its highlights with it. The store refuses changes
     * from then on, so a reader screen still open on the book cannot write the file back.
     */
    suspend fun forget() = lock.withLock {
        forgotten = true
        storage.delete()
        loaded = emptyList()
        mutableItems.value = emptyList()
    }

    private suspend fun change(id: String, edit: (Highlight) -> Highlight): Highlight? = lock.withLock {
        val items = current()
        val before = items.firstOrNull { it.id == id } ?: return@withLock null
        val after = edit(before).copy(updatedAt = now())
        save(items.map { if (it.id == id) after else it })
        after
    }

    private fun current(): List<Highlight> {
        check(!forgotten) { "The book's highlights were deleted with the book" }
        loaded?.let { return it }
        val read = storage.read().orEmpty().sortedWith(Highlight.BOOK_ORDER)
        loaded = read
        mutableItems.value = read
        return read
    }

    private fun save(next: List<Highlight>) {
        val sorted = next.sortedWith(Highlight.BOOK_ORDER)
        storage.write(sorted)
        loaded = sorted
        mutableItems.value = sorted
    }
}
