package org.sunsetware.phocid

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.PlayEventEntity
import org.sunsetware.phocid.stats.db.PlaybackOutboxEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackDailyEntity
import org.sunsetware.phocid.stats.db.TrackEntity

@RunWith(RobolectricTestRunner::class)
class StatsMaintenanceTest {

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

    private suspend fun insertTrack(uuid: String, createdAt: Long): Long =
        db.trackDao()
            .insert(
                TrackEntity(
                    uuid = uuid,
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                    durationMillis = 180_000,
                    path = "",
                    createdAt = createdAt,
                )
            )

    @Test
    fun orphanReclamationDeletesOnlyUnplayedStaleTracks() =
        runBlocking<Unit> {
            val stale = 1_000L
            val fresh = 2_000_000_000_000L
            val cutoff = 1_500L

            val unplayed = insertTrack("unplayed", stale)
            db.aliasDao().put(TrackAliasEntity(AliasKind.REL_PATH, "rel/path.flac", unplayed))

            val played = insertTrack("played", stale)
            db.totalsDao().accumulate(played, 1, 60_000, 1_234L, 1, 0, 0, 0)

            val recent = insertTrack("recent", fresh)

            val deleted = db.trackDao().deleteOrphans(cutoff)

            assertThat(deleted).isEqualTo(1)
            assertThat(db.trackDao().byRowId(unplayed)).isNull()
            // The orphan's aliases must go with it (foreign-key cascade).
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "rel/path.flac")).isNull()
            assertThat(db.trackDao().byRowId(played)).isNotNull()
            assertThat(db.trackDao().byRowId(recent)).isNotNull()
        }

    @Test
    fun orphanReclamationKeepsTracksWithDailyRowsOrRawEvents() =
        runBlocking<Unit> {
            // Retention prunes old daily rows while lifetime totals survive, but a track whose last
            // statistics live only in the raw event log must still be protected.
            val stale = 1_000L
            val cutoff = Long.MAX_VALUE / 2

            val eventOnly = insertTrack("event-only", stale)
            db.eventDao()
                .insert(
                    PlayEventEntity(trackRowId = eventOnly, playedAt = 500L, playedMillis = 1_000L)
                )

            val dailyOnly = insertTrack("daily-only", stale)
            db.dailyDao()
                .insertAll(
                    listOf(
                        TrackDailyEntity(
                            trackRowId = dailyOnly,
                            epochDay = 20_000,
                            playCount = 1,
                            playedMillis = 1_000,
                        )
                    )
                )

            val deleted = db.trackDao().deleteOrphans(cutoff)

            assertThat(deleted).isZero()
            assertThat(db.trackDao().byRowId(eventOnly)).isNotNull()
            assertThat(db.trackDao().byRowId(dailyOnly)).isNotNull()
        }

    @Test
    fun outboxTrimKeepsTheNewestRows() =
        runBlocking<Unit> {
            repeat(10) { i ->
                db.outboxDao()
                    .insert(
                        PlaybackOutboxEntity(
                            payload = byteArrayOf(i.toByte()),
                            createdAt = i.toLong(),
                        )
                    )
            }

            val deleted = db.outboxDao().trimTo(4)

            assertThat(deleted).isEqualTo(6)
            val remaining = db.outboxDao().all()
            assertThat(remaining).hasSize(4)
            assertThat(remaining.map { it.id }).containsExactly(7L, 8L, 9L, 10L)
            assertThat(db.outboxDao().count()).isEqualTo(4)
        }

    @Test
    fun outboxTrimBelowTheCapIsANoOp() =
        runBlocking<Unit> {
            repeat(3) { i ->
                db.outboxDao()
                    .insert(
                        PlaybackOutboxEntity(
                            payload = byteArrayOf(i.toByte()),
                            createdAt = i.toLong(),
                        )
                    )
            }

            assertThat(db.outboxDao().trimTo(512)).isZero()
            assertThat(db.outboxDao().count()).isEqualTo(3)
        }

    @Test
    fun hourlyBucketsAggregateEventsInSql() =
        runBlocking<Unit> {
            val track = insertTrack("track", 1_000L)
            // A multiple of the 15-minute grid: two events inside one slice, one in the next.
            val base = 1_800_000_000L
            assertThat(base % 900_000L).isZero()

            db.eventDao()
                .insert(
                    PlayEventEntity(trackRowId = track, playedAt = base, playedMillis = 10_000L)
                )
            db.eventDao()
                .insert(
                    PlayEventEntity(
                        trackRowId = track,
                        playedAt = base + 600_000L,
                        playedMillis = 20_000L,
                    )
                )
            db.eventDao()
                .insert(
                    PlayEventEntity(
                        trackRowId = track,
                        playedAt = base + 899_999L,
                        playedMillis = 30_000L,
                    )
                )
            db.eventDao()
                .insert(
                    PlayEventEntity(
                        trackRowId = track,
                        playedAt = base + 900_000L,
                        playedMillis = 40_000L,
                    )
                )

            // Bounded: a Room Flow that never emits (paused-looper invalidation) must fail here
            // rather than hang the test worker.
            val slices = withTimeout(10_000) { db.eventDao().hourlyBuckets(0).first() }

            assertThat(slices).hasSize(2)
            assertThat(slices[0].startMillis).isEqualTo(base)
            assertThat(slices[0].plays).isEqualTo(3)
            assertThat(slices[0].millis).isEqualTo(60_000L)
            assertThat(slices[1].startMillis).isEqualTo(base + 900_000L)
            assertThat(slices[1].plays).isEqualTo(1)
            assertThat(slices[1].millis).isEqualTo(40_000L)
        }
}
