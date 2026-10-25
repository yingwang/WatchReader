package com.watchreader.shared

import com.watchreader.shared.bookmark.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookmarkStoreTest {
    private class Memory : BookmarkStorage {
        var saved: BookmarkSnapshot? = null
        var fail = false
        override fun read() = saved
        override fun write(snapshot: BookmarkSnapshot) {
            if (fail) throw IOException("Disk full")
            saved = snapshot
        }
    }

    @Test fun editsSurviveRestartAndRepeatedAddDoesNotDuplicate() = runBlocking {
        val disk = Memory()
        val first = BookmarkStore(disk) { 100 }
        val mark = first.put("book", 42, "A remembered sentence")
        assertEquals(mark, first.put("book", 42, "changed excerpt"))
        val restarted = BookmarkStore(disk)
        restarted.load()
        assertEquals(listOf(mark), Bookmark.visible(restarted.records.value, "book"))
        assertTrue(Bookmark.visible(restarted.records.value, "other").isEmpty())
    }

    @Test fun offlineDeletionCannotBeResurrectedByOldSyncAndCanBeAddedAgain() = runBlocking {
        val a = BookmarkStore(Memory()) { 100 }
        val b = BookmarkStore(Memory()) { 10 }
        val original = a.put("book", 5, "line")
        b.merge(original)
        val deleted = b.remove("book", 5)!!
        assertTrue(deleted.revision > original.revision)
        a.merge(deleted)
        a.merge(original)
        assertTrue(Bookmark.visible(a.records.value, "book").isEmpty())
        val restored = a.put("book", 5, "line")
        b.merge(restored)
        b.merge(deleted)
        assertEquals(listOf(restored), Bookmark.visible(b.records.value, "book"))
    }

    @Test fun equalRevisionConflictsConvergeRegardlessOfDeliveryOrder() {
        val live = Bookmark("book", 3, "line", 100, "z")
        val deleted = live.copy(deleted = true, writerId = "a")
        assertEquals(deleted, Bookmark.merge(live, deleted))
        assertEquals(deleted, Bookmark.merge(deleted, live))
    }

    @Test fun failedWriteDoesNotPublishAnUnsavedChange() = runBlocking {
        val disk = Memory()
        val store = BookmarkStore(disk) { 100 }
        val original = store.put("book", 3, "line")
        disk.fail = true
        try { store.remove("book", 3); fail("Expected disk error") } catch (_: IOException) { }
        assertEquals(listOf(original), store.records.value)
        assertEquals(listOf(original), disk.saved!!.records)
    }

    @Test fun diskAndWireRoundTripIncludeTombstonesAndRejectMismatchedPaths() = runBlocking {
        val folder = File("build/test-fixtures/bookmarks-${UUID.randomUUID()}")
        try {
            val storage = BookmarkFileStorage(File(folder, "records.json"))
            val store = BookmarkStore(storage)
            store.put("book", 8, "云过山青 🌿")
            val tombstone = store.remove("book", 8)!!
            assertEquals(listOf(tombstone), storage.read()!!.records)
            val bytes = BookmarkCodec.encode(tombstone).toByteArray()
            assertEquals(tombstone, BookmarkSync.decode(BookmarkSync.path(tombstone), bytes))
            assertNull(BookmarkSync.decode(BookmarkSync.PREFIX + "wrong", bytes))
            assertNull(BookmarkSync.decode(BookmarkSync.path(tombstone), "broken".toByteArray()))
        } finally { folder.deleteRecursively() }
    }

    @Test fun aWriteCutShortLeavesThePreviousFileReadable() = runBlocking {
        val folder = File("build/test-fixtures/bookmarks-${UUID.randomUUID()}")
        try {
            val file = File(folder, "records.json")
            val mark = BookmarkStore(BookmarkFileStorage(file)) { 100 }.put("book", 8, "line")
            // The process died while the next snapshot was being written beside the file.
            val pending = File(folder, "records.json.pending")
            pending.writeText("{\"version\":1,\"writerId\":\"")
            val restarted = BookmarkStore(BookmarkFileStorage(file)) { 200 }
            restarted.load()
            assertEquals(listOf(mark), Bookmark.visible(restarted.records.value, "book"))
            // The next save takes the half-written file's place.
            restarted.put("book", 9, "next")
            assertFalse(pending.exists())
            assertEquals(2, BookmarkFileStorage(file).read()!!.records.size)
        } finally { folder.deleteRecursively() }
    }

    @Test fun snapshotsFromANewerVersionOrWithARecordTwiceAreRefused() {
        val record = Bookmark("book", 1, "line", 5, "writer")
        val saved = BookmarkCodec.encodeSnapshot(BookmarkSnapshot("writer", listOf(record)))
        assertEquals(listOf(record), BookmarkCodec.decodeSnapshot(saved).records)
        assertThrows(IllegalArgumentException::class.java) {
            BookmarkCodec.decodeSnapshot(saved.replace("\"version\":1", "\"version\":2"))
        }
        val twice = org.json.JSONObject(saved).also { root ->
            val records = root.getJSONArray("records")
            records.put(records.getJSONObject(0))
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.decodeSnapshot(twice) }
    }

    @Test fun corruptStoreIsNotOverwrittenAsEmpty() = runBlocking {
        val disk = object : BookmarkStorage {
            override fun read(): BookmarkSnapshot = BookmarkCodec.decodeSnapshot("{bad}")
            override fun write(snapshot: BookmarkSnapshot) { fail("Corrupt data overwritten") }
        }
        val store = BookmarkStore(disk)
        try { store.put("book", 2, "line"); fail("Corruption hidden") } catch (_: org.json.JSONException) { }
        assertTrue(store.records.value.isEmpty())
    }
}
