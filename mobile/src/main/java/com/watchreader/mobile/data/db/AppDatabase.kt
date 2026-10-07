package com.watchreader.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.ReadingMilestone
import com.watchreader.mobile.data.model.ReadingTime

@Database(entities = [Book::class, ReadingTime::class, ReadingMilestone::class], version = 7, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingStatsDao(): ReadingStatsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN totalChars INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN readOffsetChars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE book ADD COLUMN lastReadEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE book ADD COLUMN coverPath TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN tocJson TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN syncMessage TEXT")
            }
        }

        /** Reading time arrives in tables of its own; the books are not touched. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_time` (`bookId` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `millis` INTEGER NOT NULL, `chars` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bookId`, `day`, `source`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_milestone` (`bookId` TEXT NOT NULL, " +
                        "`firstOpenedEpochMs` INTEGER NOT NULL, `finishedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`bookId`))",
                )
            }
        }

        /**
         * Books keep their author. Every book already in the library stays as it is, author
         * unknown, since neither its file nor its row says who wrote it without importing again.
         * The statement is a constant so the migration test runs exactly what the app runs.
         */
        internal const val ADD_AUTHOR = "ALTER TABLE book ADD COLUMN author TEXT"

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(ADD_AUTHOR)
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "watchreader.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build().also { instance = it }
            }
        }
    }
}
