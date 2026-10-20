package com.rtls.core.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(
    entities = [QueuedLocationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RtlsDatabase : RoomDatabase() {

    abstract fun locationQueueDao(): LocationQueueDao

    companion object {
        private const val DATABASE_NAME = "rtls.db"

        fun create(context: Context): RtlsDatabase =
            Room.databaseBuilder(context, RtlsDatabase::class.java, DATABASE_NAME)
                .build()
    }
}
