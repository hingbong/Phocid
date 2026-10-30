package org.sunsetware.phocid.stats.recorder

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import java.io.File
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.sunsetware.phocid.stats.db.ActiveDayEntity
import org.sunsetware.phocid.stats.db.PlayEventEntity
import org.sunsetware.phocid.stats.db.PlaybackOutboxEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

/** One local-day bucket persisted inside a [PlaybackOutboxPayload]. */
@Serializable
internal data class PlaybackOutboxDay(
    val epochDay: Int,
    val millis: Long,
    val plays: Int,
    val lastPlayedAt: Long,
)

/**
 * CBOR snapshot of a session whose pending time could not be written at shutdown. It carries every
 * field writeSession needs, including the metadata required to resolve the track again on the next
 * start.
 */
@Serializable
internal data class PlaybackOutboxPayload(
    val uri: String,
    val filePath: String? = null,
    val size: Long,
    val modified: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val metaDurationMillis: Long,
    val label: String,
    val durationMillis: Long,
    val accumulatedMillis: Long,
    val counted: Boolean,
    val countWall: Long,
    val countPlayedMillis: Long,
    val lastWall: Long,
    val pending: List<PlaybackOutboxDay>,
)

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
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class PlaybackStatsRecorder(
    private val db: StatsDatabase,
    private val resolver: TrackResolver,
    /**
     * Store-wide lock shared with [org.sunsetware.phocid.stats.StatsManager]. Every database write
     * takes this before [writerMutex]; clear/restore takes it across the database change and the
     * recorder invalidation so no flush can commit stale pending time in between.
     */
    private val storeMutex: Mutex = Mutex(),
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
        private const val MAX_RE_RESOLVE_ATTEMPTS = 2
    }

    private val lock = Any()
    private val writerMutex = Mutex()
    private val outboxCbor = Cbor { ignoreUnknownKeys = true }

    private var current: Session? = null
    private val retired = LinkedHashMap<Long, Session>()
    private val resolutionJobs = LinkedHashMap<Long, Job>()
    private var nextSessionId = 1L
    private var totalElapsedPlayed = 0L
    private var ticker: Job? = null
    private var writeJobsInFlight = 0
    private var recoveryJob: Job? = null

    @Volatile private var playing = false
    @Volatile private var shuttingDown = false

    private data class PendingDay(
        var millis: Long = 0L,
        var plays: Int = 0,
        var lastPlayedAt: Long = 0L,
    )

    private class Session(
        val id: Long,
        val meta: TrackMeta,
        val label: String,
        val durationMillis: Long,
        var segmentWall: Long,
        var segmentElapsed: Long,
        var running: Boolean,
    ) {
        var trackRowId: Long? = null
        var rowIdStale = false
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
        var previous: Session? = null
        val session =
            synchronized(lock) {
                if (shuttingDown) return
                // Clocks are read under the state lock so callbacks apply in timestamp order even
                // when their coroutine/thread scheduling is inverted.
                val wall = wallMillis()
                val elapsed = elapsedRealtime()
                current?.let {
                    previous = it
                    accrueLocked(it, wall, elapsed)
                    retireLocked(it)
                }
                Session(
                        id = nextSessionId++,
                        meta = meta,
                        label = meta.title ?: meta.file?.name ?: meta.uri,
                        durationMillis = meta.durationMillis,
                        segmentWall = wall,
                        segmentElapsed = if (playing) elapsed else 0L,
                        running = playing,
                    )
                    .also { current = it }
            }
        previous?.let { preserveUnresolvedIfFailed(it) }
        startResolution(session, meta)
        requestFlush()
        updateTicker()
    }

    fun onResumed() {
        synchronized(lock) {
            if (shuttingDown) return
            val wall = wallMillis()
            val elapsed = elapsedRealtime()
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
        synchronized(lock) {
            if (shuttingDown) return
            val wall = wallMillis()
            val elapsed = elapsedRealtime()
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
        synchronized(lock) {
            if (shuttingDown) return
            val wall = wallMillis()
            val elapsed = elapsedRealtime()
            current?.let {
                if (it.running) accrueLocked(it, wall, elapsed)
                it.lastWall = maxOf(it.lastWall, wall)
                if (it.running) {
                    // Rebase only forward. A (stale) reading older than the accrual baseline must
                    // not rewind it, or the next tick would count the same interval twice.
                    rebaseLocked(it, wall, elapsed)
                } else {
                    it.segmentElapsed = 0L
                }
            }
        }
        requestFlush()
    }

    fun onTrackFinished() {
        var finished: Session? = null
        synchronized(lock) {
            if (shuttingDown) return
            val wall = wallMillis()
            val elapsed = elapsedRealtime()
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
        finished?.let { preserveUnresolvedIfFailed(it) }
        requestFlush()
    }

    /** Called from Service.onDestroy; blocking here avoids losing the final seconds. */
    fun onShutdown() {
        synchronized(lock) {
            if (shuttingDown) return
            val wall = wallMillis()
            val elapsed = elapsedRealtime()
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
            // already resolved was joined above; the rest is preserved by the shutdown outbox.
            val stragglers = synchronized(lock) { resolutionJobs.values.toList() }
            stragglers.forEach { it.cancel() }
            // Store lock first, then writer lock: clear/restore holds the same order, so no
            // pending time can be written after the aggregates it belonged to were cleared.
            storeMutex.withLock { writerMutex.withLock { settleAllForShutdown() } }
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
        synchronized(lock) {
            if (shuttingDown) return
            val wall = wallMillis()
            val elapsed = elapsedRealtime()
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
                // Always store-wide lock first, then the writer lock. Every recorder DB write uses
                // this order, and no lock is ever acquired in the opposite order.
                committed = storeMutex.withLock { writerMutex.withLock { writeSession(session) } }
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
        // Never move the measurement baseline backwards for a stale reading. Leaving segmentWall
        // and segmentElapsed untouched means the next real instant is counted exactly once.
        if (delta < 0L) return
        if (delta == 0L) {
            session.segmentWall = nowWall
            return
        }
        splitIntervalLocked(session, session.segmentWall, nowWall, delta)
        session.segmentWall = nowWall
        session.segmentElapsed = nowElapsed
    }

    /** Move the measurement baseline to [nowElapsed] only when that is not a rewind. */
    private fun rebaseLocked(session: Session, nowWall: Long, nowElapsed: Long) {
        if (nowElapsed < session.segmentElapsed) return
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

    /**
     * A retired session whose identity resolution failed still owns playable time. Preserve it in
     * the outbox instead of dropping it; recovery gets another chance to resolve the metadata. The
     * state lock only guards the queue flag, and the database insert uses the usual store -> writer
     * order.
     */
    private fun preserveUnresolvedIfFailed(session: Session) {
        synchronized(lock) {
            if (session.cleared || session.trackRowId != null || !session.resolutionFailed) {
                return
            }
            if (session.writeQueued) return
            session.writeQueued = true
            writeJobsInFlight++
        }
        scope.launch {
            var saved = false
            try {
                saved = storeMutex.withLock { writerMutex.withLock { persistOutbox(session) } }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e("Phocid", "Unable to preserve unresolved playback statistics", ex)
            } finally {
                synchronized(lock) {
                    session.writeQueued = false
                    writeJobsInFlight--
                }
            }
            if (saved) {
                synchronized(lock) { clearSessionLocked(session) }
            } else {
                val pending = synchronized(lock) { session.pendingMillis() }
                Log.e(
                    "Phocid",
                    "Unable to preserve unresolved playback statistics for ${session.label}: $pending ms",
                )
            }
        }
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
        job.invokeOnCompletion { synchronized(lock) { resolutionJobs.remove(session.id) } }
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
            val retired =
                synchronized(lock) {
                    session.resolutionFailed = true
                    session.retired && !session.cleared
                }
            if (retired) {
                // A retired track can no longer flush through the normal path (it has no row id),
                // but its pending time is still real. Put it in the outbox for the next start.
                val saved =
                    try {
                        storeMutex.withLock {
                            writerMutex.withLock { persistOutbox(session) }
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Log.e("Phocid", "Unable to preserve unresolved playback statistics", ex)
                        false
                    }
                if (saved) {
                    synchronized(lock) { clearSessionLocked(session) }
                } else {
                    val pending = synchronized(lock) { session.pendingMillis() }
                    Log.e(
                        "Phocid",
                        "Unable to preserve unresolved playback statistics for ${session.label}: $pending ms",
                    )
                }
            }
            return
        }

        val shouldFlush =
            synchronized(lock) {
                if (session.cleared) return
                // Read under the state lock like every other accrue point, so a resolution that
                // completes late cannot apply a stale interval after a ticker advanced the
                // baseline.
                val wall = wallMillis()
                val elapsed = elapsedRealtime()
                session.trackRowId = rowId
                // A store change can invalidate an id resolved while its fingerprint was running;
                // keep the invalidation so the first write re-resolves instead of trusting it.
                // Shutdown already accrued the current session at the capture instant. Let the
                // resolution finish so its row id is not discarded, then settle from onShutdown.
                if (session.running && !shuttingDown) accrueLocked(session, wall, elapsed)
                !shuttingDown && session.pending.isNotEmpty()
            }
        if (shouldFlush) enqueueSettle(session)
    }

    // Store-wide changes ----------------------------------------------

    /**
     * Called after the statistics store was cleared. Accrual that belonged to the cleared data must
     * not be written back, but the current session keeps running from a fresh baseline.
     */
    suspend fun onStoreCleared() {
        storeMutex.withLock { onStoreClearedLocked() }
    }

    /**
     * Store-cleared invalidation for callers that already hold the store-wide mutex
     * ([org.sunsetware.phocid.stats.StatsManager]). Kotlin's [Mutex] is not reentrant, so the public
     * entry point above must not be called from inside that critical section.
     */
    internal suspend fun onStoreClearedLocked() {
        // No shuttingDown early-return: this method is ordered against shutdown settling by the
        // store mutex. If clear wins the lock during shutdown's bounded resolution wait, pending
        // time must be dropped before shutdown can settle or outbox it.
        writerMutex.withLock {
            synchronized(lock) {
                val wall = wallMillis()
                val elapsed = elapsedRealtime()
                current?.let { resetAccrualLocked(it, wall, elapsed) }
                retired.values.toList().forEach { dropUnresolvedLocked(it) }
                totalElapsedPlayed = 0L
            }
        }
    }

    /**
     * Called after a backup was merged or replaced. A merge keeps pending time but the stored row
     * may have moved or disappeared; a replacement drops pending because the aggregate set it
     * belonged to was replaced wholesale.
     */
    suspend fun onStoreRestored(replace: Boolean) {
        storeMutex.withLock { onStoreRestoredLocked(replace) }
    }

    /** Store-restored invalidation; the caller already holds the store-wide mutex. */
    internal suspend fun onStoreRestoredLocked(replace: Boolean) {
        // See onStoreClearedLocked: restore is ordered against shutdown by the store mutex, so a
        // replace must still drop pending time that shutdown could otherwise write back.
        writerMutex.withLock {
            synchronized(lock) {
                val wall = wallMillis()
                val elapsed = elapsedRealtime()
                if (replace) {
                    current?.let { dropPendingLocked(it, wall, elapsed) }
                    retired.values.toList().forEach { dropUnresolvedLocked(it) }
                } else {
                    current?.rowIdStale = true
                    retired.values.forEach { it.rowIdStale = true }
                }
            }
        }
    }

    /**
     * Drop the unflushed accrual of [session] and restart its measurement baseline. The retained
     * metadata stays available so the next flush can bind future time to a new row.
     */
    private fun resetAccrualLocked(session: Session, nowWall: Long, nowElapsed: Long) {
        dropPendingLocked(session, nowWall, nowElapsed)
        session.accumulatedMillis = 0L
        session.counted = false
        session.countWall = 0L
        session.countPlayedMillis = 0L
    }

    /**
     * Discard time that was not persisted yet and rebase the measurement interval at the change
     * instant, so the discarded time cannot be accrued again.
     */
    private fun dropPendingLocked(session: Session, nowWall: Long, nowElapsed: Long) {
        session.pending.clear()
        session.rowIdStale = true
        session.lastWall = maxOf(session.lastWall, nowWall)
        if (session.running) {
            session.segmentWall = nowWall
            session.segmentElapsed = nowElapsed
        }
    }

    /**
     * Returns true when the transaction committed.
     *
     * When [outboxId] is non-null this is an outbox recovery write: the row is deleted inside the
     * same transaction as the aggregate updates, so a process death either keeps both the pending
     * payload and the un-applied aggregates, or removes/commits both. Normal writes pass null.
     */
    private suspend fun writeSession(session: Session, outboxId: Long? = null): Boolean {
        var resolveAttempts = 0
        while (true) {
            if (outboxId != null) {
                val stillPending =
                    try {
                        outboxRowExists(outboxId)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Log.e("Phocid", "Unable to read playback outbox row $outboxId", ex)
                        return false
                    }
                // A concurrent recovery, clear() or replace-restore already consumed the row.
                if (!stillPending) return true
            }
            val rowId =
                synchronized(lock) {
                    if (session.cleared) return false
                    if (session.pending.isEmpty()) {
                        if (session.retired) removeRetiredLocked(session)
                        return false
                    }
                    session.trackRowId ?: return false
                }
            // A row id can go stale while the session keeps running: clear/restore can replace the
            // aggregate set, and a resolver merge can delete the old row. Trust the database, not
            // the cached id.
            if (synchronized(lock) { session.rowIdStale } || !trackRowExists(rowId)) {
                if (resolveAttempts >= MAX_RE_RESOLVE_ATTEMPTS) return false
                resolveAttempts++
                if (!reResolveSession(session, rowId)) return false
                continue
            }

            val plan =
                synchronized(lock) {
                    if (session.cleared) return false
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
                        eventAt =
                            if (session.countWall > 0L) session.countWall else session.lastWall,
                        eventPlayedMillis =
                            if (session.countPlayedMillis > 0L) session.countPlayedMillis
                            else session.accumulatedMillis,
                    )
                }

            try {
                var outboxAlreadyApplied = false
                db.withTransaction {
                    // Authoritative check inside the transaction: the row may have been deleted
                    // after the pre-check by a concurrent recovery or by clear/replace.
                    if (outboxId != null && !outboxRowExists(outboxId)) {
                        outboxAlreadyApplied = true
                        return@withTransaction
                    }
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
                    // Atomic with the writes above: a crash can never leave both applied.
                    if (outboxId != null) db.outboxDao().delete(outboxId)
                }
                if (outboxAlreadyApplied) return true
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
                // Generic database failures (locked file, trigger abort, ...) keep the same row and
                // retry on the next flush. Only a row that no longer exists is stale.
                if (
                    resolveAttempts < MAX_RE_RESOLVE_ATTEMPTS &&
                        !trackRowExists(plan.trackRowId) &&
                        reResolveSession(session, plan.trackRowId)
                ) {
                    resolveAttempts++
                    continue
                }
                return false
            }
        }
    }

    /**
     * A missing row is the reliable staleness signal. If the check itself fails, keep the cached id
     * and let the write decide, so a transient database problem never triggers re-resolution.
     */
    private suspend fun trackRowExists(rowId: Long): Boolean = runCatching {
        db.trackDao().byRowId(rowId) != null
    }.getOrElse { true }

    /**
     * Re-resolve a session whose cached row id no longer points at a stored track. [staleRowId]
     * guards against overwriting an id that was refreshed through another path.
     */
    private suspend fun reResolveSession(session: Session, staleRowId: Long): Boolean {
        val meta =
            synchronized(lock) {
                if (session.cleared) return false
                if (session.trackRowId != staleRowId) return true
                session.meta
            }
        val rowId =
            try {
                resolver.resolve(meta)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e("Phocid", "Unable to re-identify track for statistics", ex)
                null
            } ?: return false
        return synchronized(lock) {
            if (session.cleared) false
            else {
                session.trackRowId = rowId
                session.resolutionFailed = false
                session.rowIdStale = false
                true
            }
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
                // A fingerprint that was still blocked when the bounded wait expired never
                // produced a row id. Retain its pending time in the durable outbox instead of
                // dropping it.
                val pending = synchronized(lock) { session.pendingMillis() }
                Log.w(
                    "Phocid",
                    "Deferring unresolved playback statistics at shutdown for ${session.label}: $pending ms",
                )
                if (persistOutbox(session)) {
                    synchronized(lock) { clearSessionLocked(session) }
                }
            } else {
                // writeSession re-resolves from the retained metadata when the row disappeared
                // while the session was alive, so shutdown does not drop pending time here.
                writeSession(session)
                val remaining = synchronized(lock) { session.pendingMillis() }
                if (remaining > 0L) {
                    Log.e(
                        "Phocid",
                        "Unable to persist statistics at shutdown for ${session.label}: $remaining ms",
                    )
                    // Do not drop the session until the outbox owns its pending time.
                    if (persistOutbox(session)) {
                        synchronized(lock) { clearSessionLocked(session) }
                    }
                } else {
                    synchronized(lock) { clearSessionLocked(session) }
                }
            }
        }
        synchronized(lock) {
            current = null
            // Sessions whose outbox insert failed stay in retirement as the last in-memory copy of
            // their pending time rather than being silently dropped.
            retired.values.removeAll { it.cleared }
        }
    }

    private fun clearSessionLocked(session: Session) {
        session.cleared = true
        if (current === session) current = null
        retired.remove(session.id)
    }

    /**
     * Persist a session snapshot to the outbox. Returns true when there is no pending time left to
     * protect, or the insert succeeded. The caller holds the store mutex, so a concurrent clear or
     * replace cannot delete the row between the snapshot and the insert.
     */
    private suspend fun persistOutbox(session: Session): Boolean {
        val payload =
            synchronized(lock) {
                if (session.cleared) return true
                val pending =
                    session.pending.entries
                        .filter { (_, day) -> day.millis > 0L || day.plays > 0 }
                        .map { (epochDay, day) ->
                            PlaybackOutboxDay(
                                epochDay = epochDay,
                                millis = day.millis,
                                plays = day.plays,
                                lastPlayedAt = day.lastPlayedAt,
                            )
                        }
                if (pending.isEmpty()) return true
                PlaybackOutboxPayload(
                    uri = session.meta.uri,
                    filePath = session.meta.file?.absolutePath,
                    size = session.meta.size,
                    modified = session.meta.modified,
                    title = session.meta.title,
                    artist = session.meta.artist,
                    album = session.meta.album,
                    metaDurationMillis = session.meta.durationMillis,
                    label = session.label,
                    durationMillis = session.durationMillis,
                    accumulatedMillis = session.accumulatedMillis,
                    counted = session.counted,
                    countWall = session.countWall,
                    countPlayedMillis = session.countPlayedMillis,
                    lastWall = session.lastWall,
                    pending = pending,
                )
            }
        return try {
            db.outboxDao()
                .insert(
                    PlaybackOutboxEntity(
                        payload = outboxCbor.encodeToByteArray(payload),
                        createdAt = wallMillis(),
                    )
                )
            true
        } catch (ex: Exception) {
            Log.e("Phocid", "Unable to write playback statistics outbox for ${session.label}", ex)
            false
        }
    }

    private suspend fun outboxRowExists(id: Long): Boolean =
        db.outboxDao().countById(id) > 0

    // Outbox recovery --------------------------------------------------

    /**
     * Start the asynchronous one-shot recovery of outbox rows. Called by
     * [org.sunsetware.phocid.stats.StatsManager] when a recorder is created; tests may call it
     * directly. The job is tracked so awaitSettledForTest() can join it.
     */
    internal fun startOutboxRecovery() {
        val job = scope.launch(start = CoroutineStart.LAZY) { recoverOutbox() }
        synchronized(lock) {
            if (shuttingDown || recoveryJob != null) {
                job.cancel()
                return
            }
            recoveryJob = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (recoveryJob === job) recoveryJob = null
            }
        }
        job.start()
    }

    private suspend fun recoverOutbox() {
        val rows =
            try {
                db.outboxDao().all()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e("Phocid", "Unable to read playback statistics outbox", ex)
                return
            }
        for (row in rows) {
            if (synchronized(lock) { shuttingDown }) return
            val payload =
                try {
                    outboxCbor.decodeFromByteArray<PlaybackOutboxPayload>(row.payload)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.e("Phocid", "Invalid playback statistics outbox row ${row.id}", ex)
                    // An unreadable payload can never be applied, so drop it instead of retrying
                    // it on every start. The delete still takes the store-wide write lock.
                    try {
                        storeMutex.withLock {
                            writerMutex.withLock { db.outboxDao().delete(row.id) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (deleteError: Exception) {
                        Log.e(
                            "Phocid",
                            "Unable to delete invalid playback statistics outbox row ${row.id}",
                            deleteError,
                        )
                    }
                    continue
                }
            // Resolve (and fingerprint) before taking the store lock; the critical section is the
            // stats transaction, not identity resolution.
            val trackRowId =
                try {
                    resolver.resolve(payload.toTrackMeta())
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.e("Phocid", "Unable to re-identify outbox track for statistics", ex)
                    null
                }
            // Leave the row for the next start when identity is unavailable.
            if (trackRowId == null) continue
            val committed =
                storeMutex.withLock {
                    writerMutex.withLock { writeSession(payload.toSession(trackRowId), row.id) }
                }
            if (!committed) {
                Log.w(
                    "Phocid",
                    "Deferring playback statistics outbox row ${row.id} to the next start",
                )
            }
        }
    }

    private fun PlaybackOutboxPayload.toTrackMeta(): TrackMeta =
        TrackMeta(
            uri = uri,
            file = filePath?.let(::File),
            size = size,
            modified = modified,
            title = title,
            artist = artist,
            album = album,
            durationMillis = metaDurationMillis,
        )

    private fun PlaybackOutboxPayload.toSession(trackRowId: Long): Session {
        val session =
            Session(
                id = 0L,
                meta = toTrackMeta(),
                label = label,
                durationMillis = durationMillis,
                segmentWall = lastWall,
                segmentElapsed = 0L,
                running = false,
            )
        session.trackRowId = trackRowId
        session.accumulatedMillis = accumulatedMillis
        session.counted = counted
        session.countWall = countWall
        session.countPlayedMillis = countPlayedMillis
        session.lastWall = lastWall
        pending.forEach { day ->
            session.pending[day.epochDay] =
                PendingDay(
                    millis = day.millis,
                    plays = day.plays,
                    lastPlayedAt = day.lastPlayedAt,
                )
        }
        session.retired = true
        return session
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

    /** Test-only access to the store-wide lock shared with StatsManager. */
    internal val storeMutexForTest: Mutex
        get() = storeMutex

    /** Test-only view of the shutdown gate used to interleave store changes deterministically. */
    internal fun isShuttingDownForTest(): Boolean = shuttingDown

    /**
     * Wait until in-flight resolutions and queued writes have settled. Tests use fake clocks and
     * this barrier instead of real sleeps.
     */
    internal suspend fun awaitSettledForTest() {
        repeat(10_000) {
            synchronized(lock) { recoveryJob }?.join()
            val jobs = synchronized(lock) { resolutionJobs.values.toList() }
            jobs.joinAll()
            val barrier = CompletableDeferred<Unit>()
            // Use the same store -> writer order as every real write so the barrier cannot sit
            // between a queued write and its transaction.
            scope.launch {
                storeMutex.withLock { writerMutex.withLock { barrier.complete(Unit) } }
            }
            barrier.await()
            val busy =
                synchronized(lock) {
                    resolutionJobs.isNotEmpty() ||
                        writeJobsInFlight > 0 ||
                        current?.writeQueued == true ||
                        retired.values.any { it.writeQueued } ||
                        recoveryJob?.isActive == true
                }
            if (!busy) return
        }
        throw IllegalStateException("PlaybackStatsRecorder did not settle")
    }
}
