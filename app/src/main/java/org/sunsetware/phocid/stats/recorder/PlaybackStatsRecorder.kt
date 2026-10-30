package org.sunsetware.phocid.stats.recorder

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.sunsetware.phocid.stats.db.ActiveDayEntity
import org.sunsetware.phocid.stats.db.PlayEventEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

/**
 * Writes playback statistics without blocking playback callbacks on the database.
 *
 * Listening time is measured with [elapsedRealtime] only. Wall-clock time is used for display
 * timestamps, local date buckets and counting. A wall-clock jump can therefore never inflate or
 * erase playback time.
 *
 * A play is counted once the session reaches min(30 s, half the track duration), with a one-second
 * floor. The count belongs to the local day on which the threshold is actually crossed, even when
 * the flush happens after midnight.
 *
 * Flush points: track transition, pause, seek, every 30 seconds while playing, and service
 * shutdown. Pending time is accumulated in local-day buckets, so a flush never assigns a whole
 * interval to the day on which it happened.
 *
 * Identity resolution (which may hash an audio file) always runs outside the state lock. A session
 * starts accruing at the callback instant even before its track row is known, and retired sessions
 * keep their own pending time until their resolution completes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatsRecorder(
    private val db: StatsDatabase,
    private val resolver: TrackResolver,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    companion object {
        private const val FLUSH_INTERVAL_MILLIS = 30_000L
        private const val MIN_PLAY_MILLIS = 30_000L
        private const val SHUTDOWN_RESOLUTION_TIMEOUT_MILLIS = 1_000L
    }

    private val lock = Any()
    private val writerMutex = Mutex()

    private var current: Session? = null
    private val retired = LinkedHashMap<Long, Session>()
    private val resolutionJobs = LinkedHashMap<Long, Job>()
    private var nextSessionId = 1L
    private var totalElapsedPlayed = 0L
    private var ticker: Job? = null
    private var writeJobsInFlight = 0

    @Volatile private var playing = false
    @Volatile private var shuttingDown = false

    private data class PendingDay(
        var millis: Long = 0L,
        var plays: Int = 0,
        var lastPlayedAt: Long = 0L,
    )

    private class Session(
        val id: Long,
        val label: String,
        val durationMillis: Long,
        var segmentWall: Long,
        var segmentElapsed: Long,
        var running: Boolean,
    ) {
        var trackRowId: Long? = null
        var resolutionFailed = false
        var accumulatedMillis = 0L
        var counted = false
        var countWall = 0L
        var countPlayedMillis = 0L
        var lastWall = segmentWall
        val pending = LinkedHashMap<Int, PendingDay>()
        var retired = false
        var writeQueued = false
        var cleared = false

        fun pendingMillis(): Long = pending.values.sumOf { it.millis }
    }

    private class FlushBucket(
        val epochDay: Int,
        val millis: Long,
        val plays: Int,
        val lastPlayedAt: Long,
    )

    private class FlushPlan(
        val trackRowId: Long,
        val buckets: List<FlushBucket>,
        val totalMillis: Long,
        val totalPlays: Int,
        val playedAt: Long,
        val eventAt: Long,
        val eventPlayedMillis: Long,
    )

    // Player callbacks -------------------------------------------------

    fun onTrackStarted(meta: TrackMeta) {
        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        var previous: Session? = null
        val session =
            synchronized(lock) {
                if (shuttingDown) return
                current?.let {
                    previous = it
                    accrueLocked(it, wall, elapsed)
                    retireLocked(it)
                }
                Session(
                        id = nextSessionId++,
                        label = meta.title ?: meta.file?.name ?: meta.uri,
                        durationMillis = meta.durationMillis,
                        segmentWall = wall,
                        segmentElapsed = if (playing) elapsed else 0L,
                        running = playing,
                    )
                    .also { current = it }
            }
        previous?.let { dropUnresolvedIfFailed(it) }
        startResolution(session, meta)
        requestFlush()
        updateTicker()
    }

    fun onResumed() {
        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        synchronized(lock) {
            if (shuttingDown) return
            playing = true
            current?.let {
                if (!it.running) {
                    it.running = true
                    it.segmentWall = wall
                    it.segmentElapsed = elapsed
                }
                it.lastWall = maxOf(it.lastWall, wall)
            }
        }
        updateTicker()
    }

    fun onPaused() {
        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        synchronized(lock) {
            if (shuttingDown) return
            playing = false
            current?.let {
                if (it.running) {
                    accrueLocked(it, wall, elapsed)
                    it.running = false
                    it.segmentElapsed = 0L
                }
                it.lastWall = maxOf(it.lastWall, wall)
            }
        }
        stopTicker()
        requestFlush()
    }

    fun onSeek() {
        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        synchronized(lock) {
            if (shuttingDown) return
            current?.let {
                if (it.running) accrueLocked(it, wall, elapsed)
                it.lastWall = maxOf(it.lastWall, wall)
                if (it.running) {
                    it.segmentWall = wall
                    it.segmentElapsed = elapsed
                } else {
                    it.segmentElapsed = 0L
                }
            }
        }
        requestFlush()
    }

    fun onTrackFinished() {
        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        var finished: Session? = null
        synchronized(lock) {
            if (shuttingDown) return
            current?.let {
                if (it.running) accrueLocked(it, wall, elapsed)
                it.running = false
                it.segmentElapsed = 0L
                it.lastWall = maxOf(it.lastWall, wall)
                retireLocked(it)
                finished = it
            }
        }
        stopTicker()
        finished?.let { dropUnresolvedIfFailed(it) }
        requestFlush()
    }

    /** Called from Service.onDestroy; blocking here avoids losing the final seconds. */
    fun onShutdown() {
        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        synchronized(lock) {
            if (shuttingDown) return
            shuttingDown = true
            playing = false
            current?.let {
                if (it.running) accrueLocked(it, wall, elapsed)
                it.running = false
                it.segmentElapsed = 0L
                it.lastWall = maxOf(it.lastWall, wall)
                retireLocked(it)
            }
        }
        stopTicker()
        runBlocking {
            // Resolutions run on the recorder scope, never on this thread. Give them a bounded
            // chance to finish, then log unresolved sessions instead of hanging service teardown.
            withTimeoutOrNull(SHUTDOWN_RESOLUTION_TIMEOUT_MILLIS) {
                while (true) {
                    val jobs = synchronized(lock) { resolutionJobs.values.toList() }
                    if (jobs.isEmpty()) break
                    jobs.joinAll()
                }
            }
            // A fingerprint that is still blocked must not bind data after teardown. Anything
            // already resolved was joined above; log and drop what remains.
            val stragglers = synchronized(lock) { resolutionJobs.values.toList() }
            stragglers.forEach { it.cancel() }
            writerMutex.withLock { settleAllForShutdown() }
        }
        scope.cancel()
    }

    // Internals --------------------------------------------------------

    private fun updateTicker() {
        if (synchronized(lock) { playing && !shuttingDown }) startTicker() else stopTicker()
    }

    private fun startTicker() {
        synchronized(lock) {
            if (shuttingDown || !playing) return
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (isActive) {
                    delay(FLUSH_INTERVAL_MILLIS)
                    tick()
                }
            }
        }
    }

    private fun stopTicker() {
        val job = synchronized(lock) { ticker.also { ticker = null } }
        job?.cancel()
    }

    /** Test hook also used by the periodic ticker. Accrues and flushes at the current instant. */
    internal fun tick() {
        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        synchronized(lock) {
            if (shuttingDown) return
            current?.let {
                if (it.running) accrueLocked(it, wall, elapsed)
                it.lastWall = maxOf(it.lastWall, wall)
            }
        }
        requestFlush()
    }

    private fun requestFlush() {
        val sessions =
            synchronized(lock) {
                if (shuttingDown) return
                buildList {
                    current?.let { if (it.pending.isNotEmpty()) add(it) }
                    retired.values
                        .filter { it.trackRowId != null && it.pending.isNotEmpty() }
                        .forEach(::add)
                }
            }
        sessions.forEach(::enqueueSettle)
    }

    private fun enqueueSettle(session: Session) {
        synchronized(lock) {
            if (shuttingDown || session.cleared || session.writeQueued) return
            if (session.trackRowId == null) return
            if (session.pending.isEmpty()) {
                if (session.retired) removeRetiredLocked(session)
                return
            }
            session.writeQueued = true
            writeJobsInFlight++
        }
        scope.launch {
            var committed = false
            try {
                committed = writerMutex.withLock { writeSession(session) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e("Phocid", "Unable to write playback statistics", ex)
            } finally {
                val resettle =
                    synchronized(lock) {
                        session.writeQueued = false
                        writeJobsInFlight--
                        committed &&
                            !shuttingDown &&
                            session.retired &&
                            !session.cleared &&
                            session.pending.isNotEmpty()
                    }
                // Time accrued while the transaction was in flight must not wait for a track
                // that has already ended to trigger another flush.
                if (resettle) enqueueSettle(session)
            }
        }
    }

    /**
     * Accrue [deltaMillis] of played time into local-day buckets. Both wall endpoints are used to
     * split at local midnight; [deltaMillis] always comes from the monotonic clock, so a wall jump
     * cannot change the total.
     */
    private fun splitIntervalLocked(
        session: Session,
        wallStart: Long,
        wallEnd: Long,
        deltaMillis: Long,
    ) {
        if (deltaMillis <= 0L) return
        val zone = zoneId()
        val effectiveEnd = if (wallEnd > wallStart) wallEnd else wallStart
        if (effectiveEnd <= wallStart) {
            addChunkLocked(
                session,
                localEpochDay(wallStart, zone),
                deltaMillis,
                wallStart,
                wallStart,
                zone,
            )
            return
        }
        val wallSpan = effectiveEnd - wallStart
        var segmentStartWall = wallStart
        var consumed = 0L
        var boundary = nextMidnight(segmentStartWall, zone)
        while (boundary < effectiveEnd) {
            val boundaryOffset = scaleToElapsed(boundary - wallStart, wallSpan, deltaMillis)
            val chunk = (boundaryOffset - consumed).coerceAtLeast(0L)
            if (chunk > 0L) {
                addChunkLocked(
                    session,
                    localEpochDay(segmentStartWall, zone),
                    chunk,
                    segmentStartWall,
                    boundary,
                    zone,
                )
                consumed += chunk
            }
            segmentStartWall = boundary
            boundary = nextMidnight(boundary, zone)
        }
        val chunk = deltaMillis - consumed
        if (chunk > 0L) {
            addChunkLocked(
                session,
                localEpochDay(segmentStartWall, zone),
                chunk,
                segmentStartWall,
                effectiveEnd,
                zone,
            )
        }
    }

    private fun addChunkLocked(
        session: Session,
        epochDay: Int,
        millis: Long,
        segmentStartWall: Long,
        segmentEndWall: Long,
        zone: ZoneId,
    ) {
        if (millis <= 0L) return
        val bucket = session.pending.getOrPut(epochDay) { PendingDay() }
        bucket.millis += millis
        bucket.lastPlayedAt = maxOf(bucket.lastPlayedAt, segmentEndWall)

        val before = session.accumulatedMillis
        session.accumulatedMillis += millis
        totalElapsedPlayed += millis

        if (session.counted) return
        val threshold = playCountThresholdMillis(session.durationMillis)
        if (session.accumulatedMillis < threshold) return
        val within = (threshold - before).coerceIn(0L, millis)
        val countWall = mapOffsetToWall(segmentStartWall, segmentEndWall, millis, within)
        val countBucket = session.pending.getOrPut(localEpochDay(countWall, zone)) { PendingDay() }
        countBucket.plays += 1
        countBucket.lastPlayedAt = maxOf(countBucket.lastPlayedAt, countWall)
        session.counted = true
        session.countWall = countWall
        session.countPlayedMillis = session.accumulatedMillis
    }

    private fun accrueLocked(session: Session, nowWall: Long, nowElapsed: Long) {
        session.lastWall = maxOf(session.lastWall, nowWall)
        if (!session.running) {
            session.segmentElapsed = 0L
            return
        }
        val delta = nowElapsed - session.segmentElapsed
        if (delta < 0L) return
        if (delta == 0L) {
            session.segmentWall = nowWall
            return
        }
        splitIntervalLocked(session, session.segmentWall, nowWall, delta)
        session.segmentWall = nowWall
        session.segmentElapsed = nowElapsed
    }

    private fun retireLocked(session: Session) {
        session.retired = true
        session.running = false
        session.segmentElapsed = 0L
        retired[session.id] = session
        if (current === session) current = null
    }

    private fun removeRetiredLocked(session: Session) {
        retired.remove(session.id)
        session.cleared = true
    }

    private fun dropUnresolvedIfFailed(session: Session) {
        val pending =
            synchronized(lock) {
                if (session.cleared || session.trackRowId != null || !session.resolutionFailed) {
                    return
                }
                dropUnresolvedLocked(session)
            }
        Log.e("Phocid", "Dropping unresolved playback statistics for ${session.label}: $pending ms")
    }

    private fun dropUnresolvedLocked(session: Session): Long {
        val pending = session.pendingMillis()
        if (current === session) current = null
        retired.remove(session.id)
        session.cleared = true
        return pending
    }

    private fun startResolution(session: Session, meta: TrackMeta) {
        val job = scope.launch(start = CoroutineStart.LAZY) { resolveSession(session, meta) }
        synchronized(lock) {
            if (shuttingDown || session.cleared) {
                job.cancel()
                return
            }
            resolutionJobs[session.id] = job
        }
        job.invokeOnCompletion {
            synchronized(lock) { resolutionJobs.remove(session.id) }
        }
        job.start()
    }

    private suspend fun resolveSession(session: Session, meta: TrackMeta) {
        if (synchronized(lock) { session.cleared }) return
        val rowId =
            try {
                resolver.resolve(meta)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e("Phocid", "Unable to identify track for statistics", ex)
                null
            }
        if (rowId == null) {
            val pending =
                synchronized(lock) {
                    session.resolutionFailed = true
                    if (session.retired) dropUnresolvedLocked(session) else 0L
                }
            if (session.retired) {
                Log.e(
                    "Phocid",
                    "Dropping unresolved playback statistics for ${session.label}: $pending ms",
                )
            }
            return
        }

        val wall = wallMillis()
        val elapsed = elapsedRealtime()
        val shouldFlush =
            synchronized(lock) {
                if (session.cleared) return
                session.trackRowId = rowId
                // Shutdown already accrued the current session at the capture instant. Let the
                // resolution finish so its row id is not discarded, then settle from onShutdown.
                if (session.running && !shuttingDown) accrueLocked(session, wall, elapsed)
                !shuttingDown && session.pending.isNotEmpty()
            }
        if (shouldFlush) enqueueSettle(session)
    }

    /** Returns true when the transaction committed. */
    private suspend fun writeSession(session: Session): Boolean {
        val plan =
            synchronized(lock) {
                if (session.cleared) return false
                val rowId = session.trackRowId ?: return false
                val buckets =
                    session.pending.entries.mapNotNull { (epochDay, pending) ->
                        if (pending.millis <= 0L && pending.plays == 0) null
                        else {
                            FlushBucket(
                                epochDay = epochDay,
                                millis = pending.millis,
                                plays = pending.plays,
                                lastPlayedAt = pending.lastPlayedAt,
                            )
                        }
                    }
                if (buckets.isEmpty()) {
                    if (session.retired) removeRetiredLocked(session)
                    return false
                }
                session.pending.clear()
                FlushPlan(
                    trackRowId = rowId,
                    buckets = buckets,
                    totalMillis = buckets.sumOf { it.millis },
                    totalPlays = buckets.sumOf { it.plays },
                    playedAt = maxOf(session.lastWall, buckets.maxOf { it.lastPlayedAt }),
                    eventAt = if (session.countWall > 0L) session.countWall else session.lastWall,
                    eventPlayedMillis =
                        if (session.countPlayedMillis > 0L) session.countPlayedMillis
                        else session.accumulatedMillis,
                )
            }

        try {
            db.withTransaction {
                for (bucket in plan.buckets) {
                    db.dailyDao()
                        .accumulate(
                            trackRowId = plan.trackRowId,
                            epochDay = bucket.epochDay,
                            playCount = bucket.plays,
                            playedMillis = bucket.millis,
                            at = bucket.lastPlayedAt,
                        )
                    // Lifetime active days are never aged out, so they outlive track_daily.
                    db.activeDayDao().insert(ActiveDayEntity(bucket.epochDay))
                }
                db.totalsDao()
                    .accumulate(
                        trackRowId = plan.trackRowId,
                        count = plan.totalPlays,
                        millis = plan.totalMillis,
                        at = plan.playedAt,
                    )
                if (plan.totalPlays > 0) {
                    db.eventDao()
                        .insert(
                            PlayEventEntity(
                                trackRowId = plan.trackRowId,
                                playedAt = plan.eventAt,
                                playedMillis = plan.eventPlayedMillis,
                            )
                        )
                }
            }
            synchronized(lock) {
                // Only drop a retired session once everything it owed has been committed.
                if (session.retired && session.pending.isEmpty()) removeRetiredLocked(session)
            }
            return true
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // Roll the whole plan back into pending so a later flush retries every bucket, the
            // total, the count flag and the play event together.
            synchronized(lock) {
                if (!session.cleared) {
                    for (bucket in plan.buckets) {
                        val pending = session.pending.getOrPut(bucket.epochDay) { PendingDay() }
                        pending.millis += bucket.millis
                        pending.plays += bucket.plays
                        pending.lastPlayedAt = maxOf(pending.lastPlayedAt, bucket.lastPlayedAt)
                    }
                }
            }
            Log.e("Phocid", "Unable to write playback statistics", ex)
            return false
        }
    }

    private suspend fun settleAllForShutdown() {
        val sessions =
            synchronized(lock) {
                (retired.values.toList() + listOfNotNull(current)).distinctBy { it.id }
            }
        for (session in sessions) {
            if (synchronized(lock) { session.cleared }) continue
            val rowId = synchronized(lock) { session.trackRowId }
            if (rowId == null) {
                val pending = synchronized(lock) { session.pendingMillis() }
                Log.e(
                    "Phocid",
                    "Unresolved playback statistics at shutdown for ${session.label}: $pending ms",
                )
                synchronized(lock) { session.cleared = true }
            } else {
                writeSession(session)
                val remaining = synchronized(lock) { session.pendingMillis() }
                if (remaining > 0L) {
                    Log.e(
                        "Phocid",
                        "Unable to persist statistics at shutdown for ${session.label}: $remaining ms",
                    )
                }
                synchronized(lock) {
                    session.cleared = true
                    retired.remove(session.id)
                }
            }
        }
        synchronized(lock) {
            current = null
            retired.clear()
        }
    }

    private fun localEpochDay(wallMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(wallMillis).atZone(zone).toLocalDate().toEpochDay().toInt()

    private fun nextMidnight(wallMillis: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(wallMillis).atZone(zone).toLocalDate()
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun scaleToElapsed(wallOffset: Long, wallSpan: Long, delta: Long): Long {
        if (wallOffset <= 0L) return 0L
        if (wallOffset >= wallSpan) return delta
        if (wallSpan == delta) return wallOffset
        return (wallOffset.toDouble() * delta / wallSpan).toLong().coerceIn(0L, delta)
    }

    private fun mapOffsetToWall(
        startWall: Long,
        endWall: Long,
        millis: Long,
        offset: Long,
    ): Long {
        if (millis <= 0L || endWall <= startWall) return startWall + offset
        if (endWall - startWall == millis) return startWall + offset
        return startWall + ((endWall - startWall).toDouble() * offset / millis).toLong()
    }

    private fun playCountThresholdMillis(durationMillis: Long): Long {
        return (durationMillis / 2).coerceAtMost(MIN_PLAY_MILLIS).coerceAtLeast(1_000L)
    }

    // Test support -----------------------------------------------------

    internal data class RecorderSnapshot(val pendingMillis: Long, val playedMillis: Long)

    internal fun snapshotForTest(): RecorderSnapshot {
        return synchronized(lock) {
            val pending =
                retired.values.sumOf { it.pendingMillis() } + (current?.pendingMillis() ?: 0L)
            RecorderSnapshot(pendingMillis = pending, playedMillis = totalElapsedPlayed)
        }
    }

    /**
     * Wait until in-flight resolutions and queued writes have settled. Tests use fake clocks and
     * this barrier instead of real sleeps.
     */
    internal suspend fun awaitSettledForTest() {
        repeat(10_000) {
            val jobs = synchronized(lock) { resolutionJobs.values.toList() }
            jobs.joinAll()
            val barrier = CompletableDeferred<Unit>()
            scope.launch { writerMutex.withLock { barrier.complete(Unit) } }
            barrier.await()
            val busy =
                synchronized(lock) {
                    resolutionJobs.isNotEmpty() ||
                        writeJobsInFlight > 0 ||
                        current?.writeQueued == true ||
                        retired.values.any { it.writeQueued }
                }
            if (!busy) return
        }
        throw IllegalStateException("PlaybackStatsRecorder did not settle")
    }
}
