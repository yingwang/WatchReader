package com.watchreader.wear.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.watchreader.wear.data.model.WearBook

@Database(entities = [WearBook::class], version = 1, exportSchema = false)
abstract class WearDatabase : RoomDatabase() {
    abstract fun wearBookDao(): WearBookDao

    companion object {
        @Volatile
        private var instance: WearDatabase? = null

        fun get(context: Context): WearDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WearDatabase::class.java,
                    "watchreader_wear.db",
                ).build().also { instance = it }
            }
        }
    }
}
