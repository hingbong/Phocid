package org.sunsetware.phocid

import androidx.room.Room
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.HourBucket
import org.sunsetware.phocid.stats.db.PlayEventEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.domain.StatsRange
import org.sunsetware.phocid.stats.domain.StatsRepository

/**
 * Hourly distribution must bucket every event by its own local hour. A single current UTC offset,
 * which the old SQL grouping applied to the whole range, misplaces events on the other side of a
 * DST transition.
 */
@RunWith(RobolectricTestRunner::class)
class StatsHourlyDistributionTest {
    private val newYork = ZoneId.of("America/New_York")
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

    private fun repository() = StatsRepository(db, zoneId = { newYork })

    private suspend fun insertTrack(uuid: String = "track"): Long =
        db.trackDao()
            .insert(
                TrackEntity(
                    uuid = uuid,
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                    durationMillis = 180_000,
                    path = "",
                    createdAt = 1,
                )
            )

    private suspend fun play(trackRowId: Long, at: ZonedDateTime, playedMillis: Long) {
        db.eventDao()
            .insert(
                PlayEventEntity(
                    trackRowId = trackRowId,
                    playedAt = at.toInstant().toEpochMilli(),
                    playedMillis = playedMillis,
                )
            )
    }

    private fun assertAllHoursInOrder(buckets: List<HourBucket>) {
        assertThat(buckets).hasSize(24)
        assertThat(buckets.map { it.hour }).containsExactlyElementsOf((0..23).toList())
    }

    private fun assertHoursEmptyExcept(buckets: List<HourBucket>, populated: Set<Int>) {
        assertThat(buckets.filter { it.hour !in populated }.map { it.plays to it.millis })
            .containsOnly(0 to 0L)
    }

    @Test
    fun fallBackRepeatedHourCountsBothEventsInHourOne() =
        runBlocking<Unit> {
            val track = insertTrack()
            // 2026-11-01: clocks go back from 02:00 EDT to 01:00 EST, so local 01:30 happens twice.
            val first = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, newYork)
            val second = first.withLaterOffsetAtOverlap()
            assertThat(first.offset).isEqualTo(ZoneOffset.ofHours(-4))
            assertThat(second.offset).isEqualTo(ZoneOffset.ofHours(-5))
            play(track, first, 60_000)
            play(track, second, 90_000)
            play(track, ZonedDateTime.of(2026, 11, 1, 15, 0, 0, 0, newYork), 30_000)

            val buckets = repository().hourlyDistribution(StatsRange.ALL).first()

            assertAllHoursInOrder(buckets)
            assertThat(buckets[1].plays).isEqualTo(2)
            assertThat(buckets[1].millis).isEqualTo(150_000)
            assertThat(buckets[15].plays).isEqualTo(1)
            assertThat(buckets[15].millis).isEqualTo(30_000)
            assertHoursEmptyExcept(buckets, setOf(1, 15))
            assertThat(buckets.sumOf { it.plays }).isEqualTo(3)
            assertThat(buckets.sumOf { it.millis }).isEqualTo(180_000)
        }

    @Test
    fun springForwardGapHasNoBucket() =
        runBlocking<Unit> {
            val track = insertTrack()
            // 2026-03-08: clocks jump from 02:00 EST to 03:00 EDT, so local hour 2 does not exist.
            play(track, ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, newYork), 10_000)
            play(track, ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, newYork), 20_000)

            val buckets = repository().hourlyDistribution(StatsRange.ALL).first()

            assertAllHoursInOrder(buckets)
            assertThat(buckets[1].plays).isEqualTo(1)
            assertThat(buckets[1].millis).isEqualTo(10_000)
            assertThat(buckets[2].plays).isZero()
            assertThat(buckets[2].millis).isZero()
            assertThat(buckets[3].plays).isEqualTo(1)
            assertThat(buckets[3].millis).isEqualTo(20_000)
            assertHoursEmptyExcept(buckets, setOf(1, 3))
        }

    @Test
    fun allRangeKeepsTheExistingSinceCutoff() =
        runBlocking<Unit> {
            val track = insertTrack()
            // ALL passes sinceMillis = 0, so a pre-epoch event must not reach any bucket.
            db.eventDao()
                .insert(
                    PlayEventEntity(trackRowId = track, playedAt = -1_000, playedMillis = 45_000)
                )
            play(track, ZonedDateTime.of(2026, 6, 15, 9, 0, 0, 0, newYork), 15_000)

            val buckets = repository().hourlyDistribution(StatsRange.ALL).first()

            assertAllHoursInOrder(buckets)
            assertThat(buckets[9].plays).isEqualTo(1)
            assertThat(buckets[9].millis).isEqualTo(15_000)
            assertThat(buckets.sumOf { it.plays }).isEqualTo(1)
            assertThat(buckets.sumOf { it.millis }).isEqualTo(15_000)
        }
}
