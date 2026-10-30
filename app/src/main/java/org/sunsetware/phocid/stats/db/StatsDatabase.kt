package org.sunsetware.phocid.stats.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities =
        [
            TrackEntity::class,
            TrackAliasEntity::class,
            PlayEventEntity::class,
            TrackDailyEntity::class,
            TrackTotalsEntity::class,
            ActiveDayEntity::class,
            FingerprintCacheEntity::class,
            PlaybackOutboxEntity::class,
        ],
    version = 3,
    exportSchema = false,
)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    abstract fun aliasDao(): TrackAliasDao

    abstract fun eventDao(): PlayEventDao

    abstract fun dailyDao(): TrackDailyDao

    abstract fun totalsDao(): TrackTotalsDao

    abstract fun activeDayDao(): ActiveDayDao

    abstract fun fingerprintCacheDao(): FingerprintCacheDao

    abstract fun outboxDao(): PlaybackOutboxDao

    abstract fun maintenanceDao(): StatsMaintenanceDao

    companion object {
        private const val NAME = "playback_stats.db"

        /**
         * 1 -> 2 adds the global lifetime active-day table. The backfill can only copy days that
         * are still inside track_daily's retention window, so migrated sets are a lower bound.
         */
        internal val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS active_days (epochDay INTEGER NOT NULL PRIMARY KEY)"
                    )
                    db.execSQL(
                        "INSERT OR IGNORE INTO active_days (epochDay) SELECT DISTINCT epochDay FROM track_daily"
                    )
                }
            }

        /**
         * 2 -> 3 adds the durable shutdown outbox. It is local recovery state, so it is not
         * backfilled and is deliberately not included in backups.
         */
        internal val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS playback_outbox (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "payload BLOB NOT NULL, " +
                            "createdAt INTEGER NOT NULL)"
                    )
                }
            }

        @Volatile private var instance: StatsDatabase? = null

        fun get(context: Context): StatsDatabase =
            instance
                ?: synchronized(this) {
                    instance ?: build(context.applicationContext).also { instance = it }
                }

        private fun build(context: Context): StatsDatabase =
            Room.databaseBuilder(context, StatsDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
         * This is a deliberate break from the old file-backed statistics. The old CBOR snapshot is
         * deleted so there is exactly one source of truth.
         */
        fun discardLegacyStats(context: Context) {
            listOf("playbackStatistics", "playbackStatistics.bak")
                .map { File(context.filesDir, it) }
                .filter { it.exists() }
                .forEach { it.delete() }
        }
    }
}
