package org.sunsetware.phocid

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackDailyEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.db.TrackTotalsEntity
import org.sunsetware.phocid.stats.domain.StatsRange
import org.sunsetware.phocid.stats.domain.StatsRepository

@RunWith(RobolectricTestRunner::class)
class StatsSummaryTest {
    private lateinit var db: StatsDatabase

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StatsDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun allTimeSummaryCountsActiveDaysFromDailyRows() = runBlocking<Unit> {
        val trackRowId =
            db.trackDao()
                .insert(
                    TrackEntity(
                        uuid = "track-uuid",
                        title = "Song",
                        artist = "Artist",
                        album = "Album",
                        durationMillis = 180_000,
                        path = "",
                        createdAt = 1,
                    )
                )
        db.totalsDao()
            .insertAll(
                listOf(
                    TrackTotalsEntity(
                        trackRowId = trackRowId,
                        totalCount = 3,
                        totalMillis = 540_000,
                        firstPlayedAt = 1_000,
                        lastPlayedAt = 3_000,
                    )
                )
            )
        db.dailyDao()
            .insertAll(
                listOf(
                    TrackDailyEntity(
                        trackRowId = trackRowId,
                        epochDay = 20_000,
                        playCount = 2,
                        playedMillis = 360_000,
                        lastPlayedAt = 2_000,
                    ),
                    TrackDailyEntity(
                        trackRowId = trackRowId,
                        epochDay = 20_001,
                        playCount = 1,
                        playedMillis = 180_000,
                        lastPlayedAt = 3_000,
                    ),
                )
            )

        val summary =
            StatsRepository(db)
                .summary(StatsRange.ALL)
                .first()

        assertThat(summary.plays).isEqualTo(3)
        assertThat(summary.millis).isEqualTo(540_000)
        assertThat(summary.uniqueTracks).isEqualTo(1)
        assertThat(summary.activeDays).isEqualTo(2)
    }
}
