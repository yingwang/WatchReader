package com.watchreader.mobile

import com.watchreader.mobile.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The move from version 6 to 7, run on the JVM against a real SQLite: the schema exports Room's
 * MigrationTestHelper needs are not kept, so the version 6 tables are made with the statements
 * Room generated for version 6, and the migration's own statement is run on them.
 */
class AppDatabaseMigrationTest {
    @Test
    fun aVersion6LibraryKeepsEveryBookWithNoAuthor() = sqlite { db ->
        V6.forEach { db.run(it) }
        db.run(
            "INSERT INTO book VALUES ('b1', 'Анна Каренина', '/books/b1.txt', 15579, 1759000000000, 'SENT', " +
                "1759000100000, 0.25, 8639, 2160, 1759000200000, '/books/b1.cover', '[{\"title\":\"Глава первая\",\"start\":0}]', NULL)",
        )
        db.run("INSERT INTO book (id, title, filePath, sizeBytes, addedEpochMs, syncStatus, lastSyncEpochMs, readProgress, totalChars, readOffsetChars, lastReadEpochMs) VALUES ('sample', 'Start here', '/books/sample.txt', 9000, 1, 'NOT_SENT', 0, 0, 9000, 0, 0)")
        db.run("INSERT INTO reading_time VALUES ('b1', '2026-10-07', 'WATCH', 600000, 4000)")
        db.run("INSERT INTO reading_milestone VALUES ('b1', 1759000150000, 0)")

        db.run(AppDatabase.ADD_AUTHOR)

        db.createStatement().executeQuery("SELECT * FROM book WHERE id = 'b1'").use { row ->
            assertTrue(row.next())
            assertEquals("Анна Каренина", row.getString("title"))
            assertEquals("/books/b1.txt", row.getString("filePath"))
            assertEquals(15579L, row.getLong("sizeBytes"))
            assertEquals(1759000000000L, row.getLong("addedEpochMs"))
            assertEquals("SENT", row.getString("syncStatus"))
            assertEquals(1759000100000L, row.getLong("lastSyncEpochMs"))
            assertEquals(0.25, row.getDouble("readProgress"), 0.0)
            assertEquals(8639, row.getInt("totalChars"))
            assertEquals(2160, row.getInt("readOffsetChars"))
            assertEquals(1759000200000L, row.getLong("lastReadEpochMs"))
            assertEquals("/books/b1.cover", row.getString("coverPath"))
            assertEquals("[{\"title\":\"Глава первая\",\"start\":0}]", row.getString("tocJson"))
            assertNull(row.getString("syncMessage"))
            assertNull(row.getString("author"))
            assertFalse(row.next())
        }
        assertEquals(2, db.count("book"))
        assertEquals(1, db.count("reading_time"))
        assertEquals(1, db.count("reading_milestone"))
    }

    /**
     * Room checks the table it opens against the one it would create, column by column, and
     * refuses a database that differs; the migrated table must be the one version 7 creates.
     */
    @Test
    fun theMigratedTableIsTheOneVersion7Creates() {
        val migrated = sqlite { db ->
            V6.forEach { db.run(it) }
            db.run(AppDatabase.ADD_AUTHOR)
            db.columns("book")
        }
        val fresh = sqlite { db ->
            db.run(V7_BOOK)
            db.columns("book")
        }
        assertEquals(fresh, migrated)
        assertEquals(listOf("author", "TEXT", "0", null, "0"), migrated.last())
    }

    private fun <T> sqlite(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite::memory:").use(block)

    private fun Connection.run(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.count(table: String): Int =
        createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }

    /** Name, type, not-null, default and primary-key position of each column, in order. */
    private fun Connection.columns(table: String): List<List<String?>> =
        createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(`$table`)").use { r ->
                buildList {
                    while (r.next()) add(listOf(r.getString("name"), r.getString("type"), r.getString("notnull"), r.getString("dflt_value"), r.getString("pk")))
                }
            }
        }

    private companion object {
        /** The tables as Room creates them at version 6 (AppDatabase_Impl, before authors were kept). */
        val V6 = listOf(
            "CREATE TABLE IF NOT EXISTS `book` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `filePath` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `addedEpochMs` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, " +
                "`lastSyncEpochMs` INTEGER NOT NULL, `readProgress` REAL NOT NULL, `totalChars` INTEGER NOT NULL, " +
                "`readOffsetChars` INTEGER NOT NULL, `lastReadEpochMs` INTEGER NOT NULL, `coverPath` TEXT, `tocJson` TEXT, " +
                "`syncMessage` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `reading_time` (`bookId` TEXT NOT NULL, `day` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`millis` INTEGER NOT NULL, `chars` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `day`, `source`))",
            "CREATE TABLE IF NOT EXISTS `reading_milestone` (`bookId` TEXT NOT NULL, `firstOpenedEpochMs` INTEGER NOT NULL, " +
                "`finishedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`bookId`))",
        )

        /** The book table as Room creates it at version 7 (AppDatabase_Impl). */
        const val V7_BOOK =
            "CREATE TABLE IF NOT EXISTS `book` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `filePath` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `addedEpochMs` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, " +
                "`lastSyncEpochMs` INTEGER NOT NULL, `readProgress` REAL NOT NULL, `totalChars` INTEGER NOT NULL, " +
                "`readOffsetChars` INTEGER NOT NULL, `lastReadEpochMs` INTEGER NOT NULL, `coverPath` TEXT, `tocJson` TEXT, " +
                "`syncMessage` TEXT, `author` TEXT, PRIMARY KEY(`id`))"
    }
}
