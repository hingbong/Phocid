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
    version = 7,
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

        /**
         * 3 -> 4 adds lifecycle counters, per-day completed time, and the local-day boundaries
         * captured when a day was first written. Legacy rows receive zero counters and null
         * boundaries; lifecycle counters are only meaningful for rows written after API 6.
         */
        internal val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (table in listOf("track_daily", "track_totals")) {
                        db.execSQL(
                            "ALTER TABLE $table ADD COLUMN startedCount INTEGER NOT NULL DEFAULT 0"
                        )
                        db.execSQL(
                            "ALTER TABLE $table ADD COLUMN completedCount INTEGER NOT NULL DEFAULT 0"
                        )
                        db.execSQL(
                            "ALTER TABLE $table ADD COLUMN skipCount INTEGER NOT NULL DEFAULT 0"
                        )
                        db.execSQL(
                            "ALTER TABLE $table ADD COLUMN completedMillis INTEGER NOT NULL DEFAULT 0"
                        )
                    }
                    db.execSQL("ALTER TABLE track_daily ADD COLUMN dayStartMs INTEGER")
                    db.execSQL("ALTER TABLE track_daily ADD COLUMN dayEndMs INTEGER")
                    db.execSQL("ALTER TABLE track_daily ADD COLUMN utcOffsetMinutes INTEGER")
                }
            }

        /** 4 -> 5 adds the earliest observed playback instant for each track/day. */
        internal val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE track_daily ADD COLUMN firstPlayedAt INTEGER")
                }
            }

        /**
         * 5 -> 6 backfills the values that older rows could not carry.
         *
         * The previous one-shot write could not know the first instant of a legacy day; the last
         * observed instant is the only recorded value and is therefore used as the earliest known
         * timestamp. A start with no listening time keeps a null first instant (lastPlayedAt is 0).
         * Rows written after API 6 always carry both instants. Rows that only recorded a start can
         * have first > last, so last is raised to first.
         *
         * Lifecycle counters are a lower bound for legacy rows: a counted play proves at least one
         * start, but the old schema never recorded skips or completions. New rows are unaffected.
         */
        internal val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE track_daily SET firstPlayedAt = lastPlayedAt " +
                            "WHERE firstPlayedAt IS NULL AND lastPlayedAt > 0"
                    )
                    db.execSQL(
                        "UPDATE track_daily SET lastPlayedAt = firstPlayedAt " +
                            "WHERE firstPlayedAt IS NOT NULL AND firstPlayedAt > lastPlayedAt"
                    )
                    db.execSQL(
                        "UPDATE track_totals SET lastPlayedAt = firstPlayedAt " +
                            "WHERE firstPlayedAt > lastPlayedAt"
                    )
                    db.execSQL(
                        "UPDATE track_daily SET startedCount = playCount " +
                            "WHERE playCount > 0 AND startedCount = 0 AND completedCount = 0 " +
                            "AND skipCount = 0 AND completedMillis = 0"
                    )
                    db.execSQL(
                        "UPDATE track_totals SET startedCount = totalCount " +
                            "WHERE totalCount > 0 AND startedCount = 0 AND completedCount = 0 " +
                            "AND skipCount = 0 AND completedMillis = 0"
                    )
                }
            }

        /**
         * 6 -> 7 repairs lifecycle counters that older writes could leave below their provable
         * lower bound. [MIGRATION_5_6] filled `startedCount` only for rows with a counted play, so
         * a row that was selected and immediately skipped kept zero forever. Every row with any
         * listening timestamp proves at least one start; completed and skipped sessions prove
         * their own starts. The update only raises counters, never lowers them.
         */
        internal val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE track_daily SET startedCount = MAX(" +
                            "startedCount, playCount, completedCount + skipCount, " +
                            "CASE WHEN lastPlayedAt > 0 THEN 1 ELSE 0 END)"
                    )
                    db.execSQL(
                        "UPDATE track_totals SET startedCount = MAX(" +
                            "startedCount, totalCount, completedCount + skipCount, " +
                            "CASE WHEN lastPlayedAt > 0 THEN 1 ELSE 0 END)"
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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
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
