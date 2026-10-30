package org.sunsetware.phocid

import androidx.room.Room
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.ActiveDayEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackDailyEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.db.TrackTotalsEntity
import org.sunsetware.phocid.stats.domain.StatsRange
import org.sunsetware.phocid.stats.domain.StatsRepository
import org.sunsetware.phocid.stats.domain.StatsSort

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

    private fun repository() = StatsRepository(db, zoneId = { ZoneOffset.UTC })

    private suspend fun insertDailyTrack(
        uuid: String,
        days: List<Int>,
        playCount: Int = 1,
        millis: Long = 60_000,
    ): Long {
        val row =
            db.trackDao()
                .insert(
                    TrackEntity(
                        uuid = uuid,
                        title = uuid,
                        artist = "Artist",
                        album = "Album",
                        durationMillis = 180_000,
                        path = "",
                        createdAt = 1,
                    )
                )
        db.dailyDao()
            .insertAll(
                days.map { day ->
                    TrackDailyEntity(
                        trackRowId = row,
                        epochDay = day,
                        playCount = playCount,
                        playedMillis = millis,
                        lastPlayedAt = day.toLong(),
                    )
                }
            )
        return row
    }

    @Test
    fun allTimeSummaryCountsActiveDaysFromGlobalTable() =
        runBlocking<Unit> {
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
            // 19_999 predates every daily row still on disk. Only the global table knows about it.
            db.activeDayDao()
                .insertAll(
                    listOf(
                        ActiveDayEntity(19_999),
                        ActiveDayEntity(20_000),
                        ActiveDayEntity(20_001),
                    )
                )

            val summary = StatsRepository(db).summary(StatsRange.ALL).first()

            assertThat(summary.plays).isEqualTo(3)
            assertThat(summary.millis).isEqualTo(540_000)
            assertThat(summary.uniqueTracks).isEqualTo(1)
            assertThat(summary.activeDays).isEqualTo(3)
        }

    @Test
    fun currentStreakIsNotLimitedByTheDailyRetentionWindow() =
        runBlocking<Unit> {
            val today = LocalDate.now().toEpochDay().toInt()
            val days = (today - 1_199..today).map { ActiveDayEntity(it) }
            db.activeDayDao().insertAll(days)

            assertThat(StatsRepository(db).currentStreak()).isEqualTo(1_200)
        }

    @Test
    fun rangeSummaryExcludesDaysAfterLocalToday() =
        runBlocking<Unit> {
            val today = LocalDate.now(ZoneOffset.UTC).toEpochDay().toInt()
            insertDailyTrack("track", listOf(today, today + 1), playCount = 2, millis = 120_000)

            val summary = repository().summary(StatsRange.DAYS_7).first()

            assertThat(summary.plays).isEqualTo(2)
            assertThat(summary.millis).isEqualTo(120_000)
            assertThat(summary.activeDays).isEqualTo(1)
        }

    @Test
    fun topTracksExcludeDaysAfterLocalToday() =
        runBlocking<Unit> {
            val today = LocalDate.now(ZoneOffset.UTC).toEpochDay().toInt()
            val todayRow = insertDailyTrack("today", listOf(today))
            insertDailyTrack("future", listOf(today + 1))

            val entries = repository().topTracks(StatsRange.DAYS_7, StatsSort.PLAYS).first()

            assertThat(entries.map { it.trackRowId }).containsExactly(todayRow)
        }

    @Test
    fun dailyTrendForAllStillExcludesDaysAfterLocalToday() =
        runBlocking<Unit> {
            val today = LocalDate.now(ZoneOffset.UTC).toEpochDay().toInt()
            insertDailyTrack("track", listOf(today - 1, today, today + 1))

            val days = repository().dailyTrend(StatsRange.ALL).first()

            assertThat(days.map { it.epochDay }).containsExactly(today - 1, today)
        }

    @Test
    fun allTimeSummaryStillCountsActiveDaysInTheTargetFuture() =
        runBlocking<Unit> {
            val today = LocalDate.now(ZoneOffset.UTC).toEpochDay().toInt()
            db.activeDayDao().insertAll(listOf(ActiveDayEntity(today), ActiveDayEntity(today + 1)))

            val summary = repository().summary(StatsRange.ALL).first()

            // active_days is lifetime history and may legitimately hold days ahead of this
            // device after a cross-timezone restore. ALL must not hide them.
            assertThat(summary.activeDays).isEqualTo(2)
        }
}
