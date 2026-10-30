package org.sunsetware.phocid

import androidx.room.Room
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackDailyEntity
import org.sunsetware.phocid.stats.recorder.PlaybackStatsRecorder
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

@RunWith(RobolectricTestRunner::class)
class PlaybackStatsRecorderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var db: StatsDatabase
    private lateinit var dispatcher: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private var recorder: PlaybackStatsRecorder? = null

    private val gates = LinkedHashMap<String, Gate>()

    private class FakeClock(var wall: Long, var elapsed: Long) {
        fun advance(millis: Long) {
            wall += millis
            elapsed += millis
        }

        fun jumpWall(delta: Long) {
            wall += delta
        }
    }

    private class Gate {
        val started = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun pass() {
            release.countDown()
        }

        fun awaitRelease(): Boolean = release.await(5, TimeUnit.SECONDS)
    }

    @Before
    fun setUp() {
        gates.clear()
        db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StatsDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        runCatching { recorder?.onShutdown() }
        db.close()
        dispatcher.close()
    }

    private fun createRecorder(
        clock: FakeClock,
        zone: ZoneId = ZoneId.of("UTC"),
    ): PlaybackStatsRecorder {
        val resolver =
            TrackResolver(
                db = db,
                fingerprint = { meta -> fingerprint(meta) },
                mediaRoots = { emptyList() },
            )
        val result =
            PlaybackStatsRecorder(
                db = db,
                resolver = resolver,
                zoneId = { zone },
                scope = scope,
                wallMillis = { clock.wall },
                elapsedRealtime = { clock.elapsed },
            )
        recorder = result
        return result
    }

    private fun fingerprint(meta: TrackMeta): String? {
        val gate = synchronized(gates) { gates[meta.uri] }
        if (gate != null) {
            gate.started.countDown()
            if (!gate.awaitRelease()) return null
        }
        return "hash:" + meta.uri + ":" + meta.modified
    }

    private fun gate(uri: String): Gate {
        val gate = Gate()
        synchronized(gates) { gates[uri] = gate }
        return gate
    }

    private fun meta(
        uri: String,
        file: File? = null,
        durationMillis: Long = 60_000L,
    ): TrackMeta =
        TrackMeta(
            uri = uri,
            file = file,
            size = 1024L,
            modified = 1L,
            title = file?.name ?: uri.substringAfterLast('/'),
            artist = "Artist",
            album = "Album",
            durationMillis = durationMillis,
        )

    private suspend fun awaitSettled(recorder: PlaybackStatsRecorder) {
        recorder.awaitSettledForTest()
    }

    private suspend fun totalMillis(): Long = db.totalsDao().all().sumOf { it.totalMillis }

    private suspend fun totalCount(): Int = db.totalsDao().all().sumOf { it.totalCount }

    private suspend fun dailyRows(): List<TrackDailyEntity> = db.dailyDao().exportSince(-100_000)

    private suspend fun activeDays(): Set<Int> = db.activeDayDao().all().map { it.epochDay }.toSet()

    private suspend fun eventPlayedAtMillis(): Long {
        return db.openHelper.readableDatabase
            .query("SELECT playedAt FROM play_events LIMIT 1")
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                cursor.getLong(0)
            }
    }

    private suspend fun awaitTrackTotal(file: File) {
        withTimeout(5_000L) {
            db.invalidationTracker.createFlow("track_totals").first {
                val rowId = db.trackDao().rowIdOfPath(file.absolutePath)
                rowId != null && db.totalsDao().byRowId(rowId) != null
            }
        }
    }

    private suspend fun assertInvariant(recorder: PlaybackStatsRecorder) {
        val snapshot = recorder.snapshotForTest()
        val persisted = totalMillis()
        val persistedPlusPending = persisted + snapshot.pendingMillis
        assertThat(kotlin.math.abs(persistedPlusPending - snapshot.playedMillis))
            .isLessThanOrEqualTo(1L)
    }

    @Test
    fun resolverDelayWhilePlayingCountsTowardNewSession() =
        runBlocking<Unit> {
            val clock = FakeClock(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), 0L)
            val recorder = createRecorder(clock)
            val uri = "content://delay/1"
            val gate = gate(uri)
            recorder.onResumed()
            recorder.onTrackStarted(meta(uri))
            assertThat(gate.started.await(5, TimeUnit.SECONDS)).isTrue()

            // The fingerprint is still running. Time spent waiting must already belong to the
            // new session because the callback instant was captured synchronously.
            clock.advance(10_000L)
            gate.pass()
            awaitSettled(recorder)

            assertThat(totalMillis()).isEqualTo(10_000L)
            assertThat(recorder.snapshotForTest().pendingMillis).isEqualTo(0L)
            assertInvariant(recorder)
        }

    @Test
    fun outOfOrderLateResolutionWritesEachTrackToItsOwnRow() =
        runBlocking<Unit> {
            val clock = FakeClock(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), 0L)
            val recorder = createRecorder(clock)
            val fileA = temporaryFolder.newFile("A.flac")
            val fileB = temporaryFolder.newFile("B.flac")
            val uriA = "content://out-of-order/a"
            val uriB = "content://out-of-order/b"
            val gateA = gate(uriA)
            val gateB = gate(uriB)

            recorder.onResumed()
            recorder.onTrackStarted(meta(uriA, fileA))
            assertThat(gateA.started.await(5, TimeUnit.SECONDS)).isTrue()

            clock.advance(10_000L)
            // A is retired while its identity is still unresolved. B starts immediately and owns
            // the following time.
            recorder.onTrackStarted(meta(uriB, fileB))
            assertThat(gateB.started.await(5, TimeUnit.SECONDS)).isTrue()
            clock.advance(5_000L)

            // B resolves first. Its write must land on B, not on the retired A.
            gateB.pass()
            awaitTrackTotal(fileB)
            gateA.pass()
            awaitSettled(recorder)

            val rowA = db.trackDao().rowIdOfPath(fileA.absolutePath)!!
            val rowB = db.trackDao().rowIdOfPath(fileB.absolutePath)!!
            assertThat(db.totalsDao().byRowId(rowA)!!.totalMillis).isEqualTo(10_000L)
            assertThat(db.totalsDao().byRowId(rowB)!!.totalMillis).isEqualTo(5_000L)
            assertInvariant(recorder)
        }

    @Test
    fun wallClockJumpsDoNotChangePlayedDuration() =
        runBlocking<Unit> {
            val clock = FakeClock(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), 0L)
            val recorder = createRecorder(clock)
            recorder.onResumed()
            recorder.onTrackStarted(meta("content://wall/1"))
            awaitSettled(recorder)

            clock.advance(10_000L)
            clock.jumpWall(3_600_000L)
            recorder.onSeek()
            awaitSettled(recorder)

            clock.advance(5_000L)
            clock.jumpWall(-7_200_000L)
            recorder.onPaused()
            awaitSettled(recorder)

            assertThat(totalMillis()).isEqualTo(15_000L)
            assertInvariant(recorder)
        }

    @Test
    fun midnightSplitsDurationAndAssignsCountToThresholdDay() =
        runBlocking<Unit> {
            val zone = ZoneId.of("America/New_York")
            val start = ZonedDateTime.of(2026, 1, 1, 23, 59, 45, 0, zone).toInstant()
            val clock = FakeClock(start.toEpochMilli(), 0L)
            val recorder = createRecorder(clock, zone)
            recorder.onResumed()
            recorder.onTrackStarted(meta("content://midnight/1", durationMillis = 60_000L))
            awaitSettled(recorder)

            // 15 s remain on Jan 1, 25 s belong to Jan 2. The 30 s play threshold is reached at
            // 00:00:15 on Jan 2, so the count belongs there even though the flush is later.
            clock.advance(40_000L)
            recorder.onPaused()
            awaitSettled(recorder)

            val day1 = LocalDate.of(2026, 1, 1).toEpochDay().toInt()
            val day2 = LocalDate.of(2026, 1, 2).toEpochDay().toInt()
            val rows = dailyRows().associateBy { it.epochDay }
            assertThat(rows).containsKeys(day1, day2)
            assertThat(rows[day1]!!.playedMillis).isEqualTo(15_000L)
            assertThat(rows[day1]!!.playCount).isEqualTo(0)
            assertThat(rows[day2]!!.playedMillis).isEqualTo(25_000L)
            assertThat(rows[day2]!!.playCount).isEqualTo(1)
            assertThat(activeDays()).containsExactlyInAnyOrder(day1, day2)
            assertThat(totalMillis()).isEqualTo(40_000L)
            assertThat(totalCount()).isEqualTo(1)
            assertThat(db.eventDao().count()).isEqualTo(1L)
            assertThat(eventPlayedAtMillis())
                .isEqualTo(
                    ZonedDateTime.of(2026, 1, 2, 0, 0, 15, 0, zone).toInstant().toEpochMilli()
                )
            assertInvariant(recorder)
        }

    @Test
    fun dstFallBackKeepsDurationInsideOneLocalDayBucket() =
        runBlocking<Unit> {
            val zone = ZoneId.of("America/New_York")
            // 01:30 EDT; one real hour later the local clock is 01:30 EST again.
            val start = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, zone).toInstant()
            val clock = FakeClock(start.toEpochMilli(), 0L)
            val recorder = createRecorder(clock, zone)
            recorder.onResumed()
            recorder.onTrackStarted(meta("content://dst/1", durationMillis = 3_600_000L))
            awaitSettled(recorder)

            clock.advance(3_600_000L)
            recorder.onPaused()
            awaitSettled(recorder)

            val day = LocalDate.of(2026, 11, 1).toEpochDay().toInt()
            val rows = dailyRows()
            assertThat(rows).hasSize(1)
            assertThat(rows[0].epochDay).isEqualTo(day)
            assertThat(rows[0].playedMillis).isEqualTo(3_600_000L)
            assertThat(rows[0].playCount).isEqualTo(1)
            assertThat(totalMillis()).isEqualTo(3_600_000L)
            assertInvariant(recorder)
        }

    @Test
    fun databaseFailurePreservesAllPendingAndRetryPersistsIt() =
        runBlocking<Unit> {
            val clock = FakeClock(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), 0L)
            val recorder = createRecorder(clock)
            recorder.onResumed()
            recorder.onTrackStarted(meta("content://db-failure/1", durationMillis = 60_000L))
            awaitSettled(recorder)

            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_track_daily BEFORE INSERT ON track_daily " +
                    "BEGIN SELECT RAISE(ABORT, 'boom'); END"
            )
            clock.advance(35_000L)
            recorder.onPaused()
            awaitSettled(recorder)

            // The transaction rolled back completely: no duration, count, active day or event.
            assertThat(totalMillis()).isEqualTo(0L)
            assertThat(totalCount()).isEqualTo(0)
            assertThat(db.eventDao().count()).isEqualTo(0L)
            assertThat(activeDays()).isEmpty()
            val failed = recorder.snapshotForTest()
            assertThat(failed.pendingMillis).isEqualTo(35_000L)
            assertThat(failed.playedMillis).isEqualTo(35_000L)

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_track_daily")
            recorder.tick()
            awaitSettled(recorder)

            assertThat(totalMillis()).isEqualTo(35_000L)
            assertThat(totalCount()).isEqualTo(1)
            assertThat(db.eventDao().count()).isEqualTo(1L)
            assertThat(dailyRows().single().playCount).isEqualTo(1)
            assertThat(activeDays()).isNotEmpty()
            assertThat(recorder.snapshotForTest().pendingMillis).isEqualTo(0L)
            assertInvariant(recorder)
        }

    @Test
    fun shutdownSettlesRetiredAndCurrentResolvedSessions() =
        runBlocking<Unit> {
            val clock = FakeClock(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), 0L)
            val recorder = createRecorder(clock)
            val fileA = temporaryFolder.newFile("ShutdownA.flac")
            val fileB = temporaryFolder.newFile("ShutdownB.flac")

            recorder.onResumed()
            recorder.onTrackStarted(meta("content://shutdown/a", fileA))
            awaitSettled(recorder)

            clock.advance(5_000L)
            // A becomes a retired resolved session while B is current.
            recorder.onTrackStarted(meta("content://shutdown/b", fileB))
            clock.advance(3_000L)
            recorder.onShutdown()

            val rowA = db.trackDao().rowIdOfPath(fileA.absolutePath)!!
            val rowB = db.trackDao().rowIdOfPath(fileB.absolutePath)!!
            assertThat(db.totalsDao().byRowId(rowA)!!.totalMillis).isEqualTo(5_000L)
            assertThat(db.totalsDao().byRowId(rowB)!!.totalMillis).isEqualTo(3_000L)
            assertThat(recorder.snapshotForTest().pendingMillis).isEqualTo(0L)
        }

    @Test
    fun persistedPlusPendingEqualsElapsedPlayedAtEveryBoundary() =
        runBlocking<Unit> {
            val clock = FakeClock(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), 0L)
            val recorder = createRecorder(clock)
            recorder.onResumed()
            recorder.onTrackStarted(meta("content://invariant/1", durationMillis = 180_000L))
            awaitSettled(recorder)
            assertInvariant(recorder)

            clock.advance(4_000L)
            recorder.tick()
            awaitSettled(recorder)
            assertInvariant(recorder)

            clock.advance(31_000L)
            recorder.tick()
            awaitSettled(recorder)
            assertInvariant(recorder)

            clock.advance(7_000L)
            recorder.onSeek()
            awaitSettled(recorder)
            assertInvariant(recorder)

            clock.advance(6_000L)
            recorder.onPaused()
            awaitSettled(recorder)
            assertInvariant(recorder)

            clock.advance(2_000L)
            recorder.onResumed()
            clock.advance(3_000L)
            recorder.onTrackFinished()
            awaitSettled(recorder)
            assertInvariant(recorder)
        }
}
