package org.sunsetware.phocid.sortkey

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SortKeyEntity::class], version = 1, exportSchema = false)
abstract class SortKeyDatabase : RoomDatabase() {
    abstract fun sortKeyDao(): SortKeyDao

    companion object {
        private const val NAME = "sort_keys.db"

        @Volatile private var instance: SortKeyDatabase? = null

        fun get(context: Context): SortKeyDatabase =
            instance
                ?: synchronized(this) {
                    instance ?: build(context.applicationContext).also { instance = it }
                }

        private fun build(context: Context): SortKeyDatabase =
            Room.databaseBuilder(context, SortKeyDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
