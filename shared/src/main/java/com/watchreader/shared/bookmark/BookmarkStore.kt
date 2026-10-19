package com.watchreader.shared.bookmark

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface BookmarkStorage {
    fun read(): BookmarkSnapshot?
    fun write(snapshot: BookmarkSnapshot)
}

/** Shared by UI and sync in one process. Every observable change has already reached disk. */
class BookmarkStore(private val storage: BookmarkStorage, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private var snapshot: BookmarkSnapshot? = null
    private val mutableRecords = MutableStateFlow<List<Bookmark>>(emptyList())
    val records = mutableRecords.asStateFlow()

    suspend fun load() = lock.withLock { loaded(); Unit }

    suspend fun put(bookId: String, offset: Int, excerpt: String): Bookmark = lock.withLock {
        val current = loaded()
        val previous = current.records.find { it.bookId == bookId && it.offset == offset }
        if (previous != null && !previous.deleted) return@withLock previous
        save(current, Bookmark(bookId, offset, excerpt, nextRevision(current), current.writerId))
    }

    suspend fun remove(bookId: String, offset: Int): Bookmark? = lock.withLock {
        val current = loaded()
        val previous = current.records.find { it.bookId == bookId && it.offset == offset } ?: return@withLock null
        if (previous.deleted) return@withLock previous
        save(current, previous.copy(deleted = true, revision = nextRevision(current), writerId = current.writerId))
    }

    suspend fun merge(incoming: Bookmark): Boolean = lock.withLock {
        val current = loaded()
        val previous = current.records.find { it.bookId == incoming.bookId && it.offset == incoming.offset }
        val merged = Bookmark.merge(previous, incoming)
        if (merged == previous) return@withLock false
        save(current, merged)
        true
    }

    /** Republish after a failed send or process death. Repeated identical DataItems are harmless. */
    suspend fun locallyAuthored(): List<Bookmark> = lock.withLock {
        loaded().let { state -> state.records.filter { it.writerId == state.writerId } }
    }

    private fun loaded(): BookmarkSnapshot {
        snapshot?.let { return it }
        val restored = storage.read() ?: BookmarkSnapshot(UUID.randomUUID().toString(), emptyList())
        snapshot = restored
        mutableRecords.value = restored.records
        return restored
    }

    private fun nextRevision(current: BookmarkSnapshot): Long =
        maxOf(1L, now(), (current.records.maxOfOrNull { it.revision } ?: 0L) + 1)

    private fun save(current: BookmarkSnapshot, record: Bookmark): Bookmark {
        val next = current.copy(records = current.records.filterNot { it.key == record.key } + record)
        storage.write(next)
        snapshot = next
        mutableRecords.value = next.records
        return record
    }
}
