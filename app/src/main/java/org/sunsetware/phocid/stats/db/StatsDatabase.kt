package org.sunsetware.phocid.stats.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

@Database(
    entities = [
        TrackEntity::class,
        TrackAliasEntity::class,
        PlayEventEntity::class,
        TrackDailyEntity::class,
        TrackTotalsEntity::class,
        FingerprintCacheEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun aliasDao(): TrackAliasDao
    abstract fun eventDao(): PlayEventDao
    abstract fun dailyDao(): TrackDailyDao
    abstract fun totalsDao(): TrackTotalsDao
    abstract fun fingerprintCacheDao(): FingerprintCacheDao
    abstract fun maintenanceDao(): StatsMaintenanceDao

    companion object {
        private const val NAME = "playback_stats.db"

        @Volatile private var instance: StatsDatabase? = null

        fun get(context: Context): StatsDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: build(context.applicationContext).also { instance = it }
                }

        private fun build(context: Context): StatsDatabase =
            Room.databaseBuilder(context, StatsDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .addCallback(
                    object : Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }
                    }
                )
                .build()

        /**
         * This is a deliberate break from the old file-backed statistics. The old CBOR
         * snapshot is deleted so there is exactly one source of truth.
         */
        fun discardLegacyStats(context: Context) {
            listOf("playbackStatistics", "playbackStatistics.bak")
                .map { File(context.filesDir, it) }
                .filter { it.exists() }
                .forEach { it.delete() }
        }
    }
}
