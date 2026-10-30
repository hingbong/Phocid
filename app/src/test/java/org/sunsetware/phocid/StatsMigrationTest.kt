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
    }
}
