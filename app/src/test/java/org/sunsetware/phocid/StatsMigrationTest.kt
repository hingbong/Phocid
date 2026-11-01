package org.sunsetware.phocid

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.StatsDatabase

@RunWith(RobolectricTestRunner::class)
class StatsMigrationTest {
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val configuration =
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {}

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {}
                    }
                )
                .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun migrationCopiesDistinctEpochDaysFromTrackDaily() {
        val db = helper.writableDatabase
        db.execSQL(CREATE_TRACK_DAILY)
        db.execSQL("INSERT INTO track_daily VALUES (1, 20000, 1, 1000, 10)")
        db.execSQL("INSERT INTO track_daily VALUES (2, 20000, 2, 2000, 20)")
        db.execSQL("INSERT INTO track_daily VALUES (1, 20001, 1, 1000, 30)")
        db.execSQL("INSERT INTO track_daily VALUES (3, 19999, 1, 1000, 5)")

        StatsDatabase.MIGRATION_1_2.migrate(db)

        assertThat(activeDays(db)).containsExactly(19999, 20000, 20001)
    }

    @Test
    fun migrationCreatesAnEmptyTableWhenTrackDailyIsEmpty() {
        val db = helper.writableDatabase
        db.execSQL(CREATE_TRACK_DAILY)

        StatsDatabase.MIGRATION_1_2.migrate(db)

        assertThat(activeDays(db)).isEmpty()
    }

    @Test
    fun migration2To3CreatesEmptyPlaybackOutboxTable() {
        val db = helper.writableDatabase

        StatsDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT COUNT(*) FROM playback_outbox").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }

        // The migration must create a usable table with the Room-declared columns, including an
        // auto-generated id, the CBOR blob and the insertion timestamp.
        db.execSQL("INSERT INTO playback_outbox (payload, createdAt) VALUES (X'0102', 123)")
        db.query("SELECT id, payload, createdAt FROM playback_outbox").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1L)
            assertThat(cursor.getBlob(1)).containsExactly(1.toByte(), 2.toByte())
            assertThat(cursor.getLong(2)).isEqualTo(123L)
        }
    }

    @Test
    fun migration4To5AddsFirstPlayedAtWithoutInventingAValue() {
        val db = helper.writableDatabase
        db.execSQL(CREATE_TRACK_DAILY)
        db.execSQL(CREATE_TRACK_TOTALS)
        StatsDatabase.MIGRATION_3_4.migrate(db)
        StatsDatabase.MIGRATION_4_5.migrate(db)
        db.execSQL(
            "INSERT INTO track_daily (trackRowId, epochDay, playCount, playedMillis, lastPlayedAt) " +
                "VALUES (1, 20000, 1, 1000, 10)"
        )

        db.query("SELECT firstPlayedAt FROM track_daily").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
    }

    @Test
    fun migration5To6BackfillsLegacyTimestampsAndCounters() {
        val db = helper.writableDatabase
        db.execSQL(CREATE_TRACK_DAILY_V5)
        db.execSQL(CREATE_TRACK_TOTALS_V5)
        db.execSQL(
            "INSERT INTO track_daily (trackRowId, epochDay, playCount, playedMillis, lastPlayedAt, " +
                "firstPlayedAt, startedCount, completedCount, skipCount, completedMillis) " +
                "VALUES (1, 20000, 3, 3000, 100, NULL, 0, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO track_daily (trackRowId, epochDay, playCount, playedMillis, lastPlayedAt, " +
                "firstPlayedAt, startedCount, completedCount, skipCount, completedMillis) " +
                "VALUES (2, 20000, 1, 1000, 100, 200, 0, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO track_daily (trackRowId, epochDay, playCount, playedMillis, lastPlayedAt, " +
                "firstPlayedAt, startedCount, completedCount, skipCount, completedMillis) " +
                "VALUES (3, 20000, 0, 0, 0, NULL, 1, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO track_totals VALUES (1, 3, 3000, 1, 100, 0, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO track_totals VALUES (2, 1, 1000, 200, 100, 0, 0, 0, 0)"
        )

        StatsDatabase.MIGRATION_5_6.migrate(db)

        db.query(
            "SELECT trackRowId, firstPlayedAt, lastPlayedAt, startedCount FROM track_daily " +
                "ORDER BY trackRowId"
        ).use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1)
            assertThat(cursor.getLong(1)).isEqualTo(100)
            assertThat(cursor.getLong(2)).isEqualTo(100)
            assertThat(cursor.getInt(3)).isEqualTo(3)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(2)
            assertThat(cursor.getLong(1)).isEqualTo(200)
            assertThat(cursor.getLong(2)).isEqualTo(200)
            assertThat(cursor.getInt(3)).isEqualTo(1)

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(3)
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.getLong(2)).isEqualTo(0)
            assertThat(cursor.getInt(3)).isEqualTo(1)
        }
        db.query("SELECT startedCount, lastPlayedAt FROM track_totals ORDER BY trackRowId").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(3)
            assertThat(cursor.getLong(1)).isEqualTo(100)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
            assertThat(cursor.getLong(1)).isEqualTo(200)
        }
    }

    @Test
    fun migration6To7RaisesLifecycleCountersToTheirLowerBound() {
        val db = helper.writableDatabase
        db.execSQL(CREATE_TRACK_DAILY_V6)
        db.execSQL(CREATE_TRACK_TOTALS_V6)
        db.execSQL(
            "INSERT INTO track_daily (trackRowId, epochDay, playCount, playedMillis, " +
                "lastPlayedAt, firstPlayedAt, startedCount, completedCount, skipCount, " +
                "completedMillis) VALUES " +
                "(1, 20000, 0, 0, 100, NULL, 0, 0, 0, 0), " +
                "(2, 20000, 3, 3000, 100, NULL, 0, 1, 0, 0), " +
                "(3, 20000, 0, 1000, 100, NULL, 0, 0, 2, 0), " +
                "(4, 20000, 0, 0, 0, NULL, 0, 0, 0, 0), " +
                "(5, 20000, 2, 2000, 100, NULL, 5, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO track_totals VALUES " +
                "(1, 0, 0, 0, 100, 0, 0, 0, 0), " +
                "(2, 3, 3000, 0, 100, 0, 1, 0, 0), " +
                "(3, 0, 1000, 0, 100, 0, 0, 2, 0), " +
                "(4, 0, 0, 0, 0, 0, 0, 0, 0), " +
                "(5, 2, 2000, 0, 100, 5, 0, 0, 0)"
        )

        StatsDatabase.MIGRATION_6_7.migrate(db)

        db.query("SELECT trackRowId, startedCount FROM track_daily ORDER BY trackRowId").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1)
            assertThat(cursor.getInt(1)).isEqualTo(1)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(2)
            assertThat(cursor.getInt(1)).isEqualTo(3)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(3)
            assertThat(cursor.getInt(1)).isEqualTo(2)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(4)
            assertThat(cursor.getInt(1)).isEqualTo(0)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(5)
            assertThat(cursor.getInt(1)).isEqualTo(5)
            assertThat(cursor.moveToNext()).isFalse()
        }
        db.query("SELECT trackRowId, startedCount FROM track_totals ORDER BY trackRowId").use { cursor ->
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1)
            assertThat(cursor.getInt(1)).isEqualTo(1)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(2)
            assertThat(cursor.getInt(1)).isEqualTo(3)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(3)
            assertThat(cursor.getInt(1)).isEqualTo(2)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(4)
            assertThat(cursor.getInt(1)).isEqualTo(0)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(5)
            assertThat(cursor.getInt(1)).isEqualTo(5)
            assertThat(cursor.moveToNext()).isFalse()
        }
    }

    @Test
    fun migration3To4AddsLifecycleAndDayBoundaryColumns() {
        val db = helper.writableDatabase
        db.execSQL(CREATE_TRACK_DAILY)
        db.execSQL(CREATE_TRACK_TOTALS)
        db.execSQL("INSERT INTO track_daily VALUES (1, 20000, 1, 1000, 10)")
        db.execSQL("INSERT INTO track_totals VALUES (1, 1, 1000, 10, 10)")

        StatsDatabase.MIGRATION_3_4.migrate(db)

        db.query("PRAGMA table_info(track_daily)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
            assertThat(columns)
                .contains(
                    "startedCount",
                    "completedCount",
                    "skipCount",
                    "completedMillis",
                    "dayStartMs",
                    "dayEndMs",
                    "utcOffsetMinutes",
                )
        }
        db.query(
            "SELECT startedCount, completedCount, skipCount, completedMillis, " +
                "dayStartMs, dayEndMs, utcOffsetMinutes FROM track_daily"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
            assertThat(cursor.getInt(1)).isEqualTo(0)
            assertThat(cursor.getInt(2)).isEqualTo(0)
            assertThat(cursor.getLong(3)).isEqualTo(0L)
            assertThat(cursor.isNull(4)).isTrue()
            assertThat(cursor.isNull(5)).isTrue()
            assertThat(cursor.isNull(6)).isTrue()
        }
        db.query("SELECT startedCount, completedCount, skipCount, completedMillis FROM track_totals")
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
                assertThat(cursor.getInt(1)).isEqualTo(0)
                assertThat(cursor.getInt(2)).isEqualTo(0)
                assertThat(cursor.getLong(3)).isEqualTo(0L)
            }
    }

    private fun activeDays(db: SupportSQLiteDatabase): List<Int> =
        db.query("SELECT epochDay FROM active_days ORDER BY epochDay").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
        }

    private companion object {
        const val CREATE_TRACK_DAILY =
            "CREATE TABLE track_daily (" +
                "trackRowId INTEGER NOT NULL, " +
                "epochDay INTEGER NOT NULL, " +
                "playCount INTEGER NOT NULL, " +
                "playedMillis INTEGER NOT NULL, " +
                "lastPlayedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(trackRowId, epochDay))"

        const val CREATE_TRACK_TOTALS =
            "CREATE TABLE track_totals (" +
                "trackRowId INTEGER NOT NULL PRIMARY KEY, " +
                "totalCount INTEGER NOT NULL, " +
                "totalMillis INTEGER NOT NULL, " +
                "firstPlayedAt INTEGER NOT NULL, " +
                "lastPlayedAt INTEGER NOT NULL)"

        const val CREATE_TRACK_DAILY_V5 =
            "CREATE TABLE track_daily (" +
                "trackRowId INTEGER NOT NULL, " +
                "epochDay INTEGER NOT NULL, " +
                "playCount INTEGER NOT NULL, " +
                "playedMillis INTEGER NOT NULL, " +
                "lastPlayedAt INTEGER NOT NULL, " +
                "firstPlayedAt INTEGER, " +
                "startedCount INTEGER NOT NULL DEFAULT 0, " +
                "completedCount INTEGER NOT NULL DEFAULT 0, " +
                "skipCount INTEGER NOT NULL DEFAULT 0, " +
                "completedMillis INTEGER NOT NULL DEFAULT 0, " +
                "dayStartMs INTEGER, " +
                "dayEndMs INTEGER, " +
                "utcOffsetMinutes INTEGER, " +
                "PRIMARY KEY(trackRowId, epochDay))"

        const val CREATE_TRACK_TOTALS_V5 =
            "CREATE TABLE track_totals (" +
                "trackRowId INTEGER NOT NULL PRIMARY KEY, " +
                "totalCount INTEGER NOT NULL, " +
                "totalMillis INTEGER NOT NULL, " +
                "firstPlayedAt INTEGER NOT NULL, " +
                "lastPlayedAt INTEGER NOT NULL, " +
                "startedCount INTEGER NOT NULL DEFAULT 0, " +
                "completedCount INTEGER NOT NULL DEFAULT 0, " +
                "skipCount INTEGER NOT NULL DEFAULT 0, " +
                "completedMillis INTEGER NOT NULL DEFAULT 0)"

        const val CREATE_TRACK_DAILY_V6 = CREATE_TRACK_DAILY_V5

        const val CREATE_TRACK_TOTALS_V6 = CREATE_TRACK_TOTALS_V5
    }
}
