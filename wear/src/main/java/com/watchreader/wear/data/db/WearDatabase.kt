package com.watchreader.wear.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.watchreader.wear.data.model.ReadingMilestone
import com.watchreader.wear.data.model.ReadingTime
import com.watchreader.wear.data.model.WearBook

@Database(entities = [WearBook::class, ReadingTime::class, ReadingMilestone::class], version = 2, exportSchema = false)
abstract class WearDatabase : RoomDatabase() {
    abstract fun wearBookDao(): WearBookDao
    abstract fun readingStatsDao(): ReadingStatsDao

    companion object {
        @Volatile
        private var instance: WearDatabase? = null

        /** Reading time arrives in tables of its own; the books and their places are not touched. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
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

        fun get(context: Context): WearDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WearDatabase::class.java,
                    "watchreader_wear.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
