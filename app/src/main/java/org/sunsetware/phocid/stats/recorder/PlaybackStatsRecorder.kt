package org.sunsetware.phocid.stats.recorder

import android.util.Log
import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sunsetware.phocid.stats.db.PlayEventEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

/**
 * Writes playback statistics with a debounce. It never touches the database from a
 * per-second playback callback.
 *
 * A play is counted once the session reaches min(30 s, half the track duration), with a
 * one-second floor. Listening time below that threshold is still added to [playedMillis].
 *
 * Flush points: track transition, pause, seek, every 30 seconds while playing, and service
 * shutdown. Seeking settles the elapsed wall-clock time first so time does not distort.
 */
class PlaybackStatsRecorder(
    private val db: StatsDatabase,
    private val resolver: TrackResolver,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        private const val FLUSH_INTERVAL_MILLIS = 30_000L
        private const val MIN_PLAY_MILLIS = 30_000L
        private const val MIN_PLAY_RATIO = 0.5
        private const val NOISE_FLOOR_MILLIS = 3_000L
    }

    private val mutex = Mutex()
    private var session: Session? = null
    private var ticker: Job? = null

    @Volatile private var playing = false

    private class Session(
        val trackRowId: Long,
        val durationMillis: Long,
        var accumulatedMillis: Long = 0,
        var pendingMillis: Long = 0,
        var countedThisSession: Boolean = false,
        var lastResumeAt: Long = 0,
    )

    // Player callbacks -------------------------------------------------

    fun onTrackStarted(meta: TrackMeta) {
        scope.launch {
            val rowId =
                mutex.withLock {
                    flushLocked(System.currentTimeMillis())
                    session = null
                    try {
                        resolver.resolve(meta)
                    } catch (ex: Exception) {
                        Log.e("Phocid", "Unable to identify track for statistics", ex)
                        return@withLock null
                    }
                } ?: return@launch

            mutex.withLock {
                val now = System.currentTimeMillis()
                session =
                    Session(
                        trackRowId = rowId,
                        durationMillis = meta.durationMillis,
                        lastResumeAt = if (playing) now else 0,
                    )
            }
            updateTicker()
        }
    }

    fun onResumed() {
        playing = true
        scope.launch {
            mutex.withLock {
                val current = session ?: return@withLock
                if (current.lastResumeAt <= 0) current.lastResumeAt = System.currentTimeMillis()
            }
        }
        updateTicker()
    }

    fun onPaused() {
        playing = false
        stopTicker()
        scope.launch {
            mutex.withLock {
                flushLocked(System.currentTimeMillis())
                session?.lastResumeAt = 0
            }
        }
    }

    fun onTrackFinished() {
        stopTicker()
        scope.launch {
            mutex.withLock {
                flushLocked(System.currentTimeMillis())
                session = null
            }
        }
    }

    fun onSeek() {
        scope.launch {
            mutex.withLock {
                val current = session ?: return@withLock
                accrueLocked(System.currentTimeMillis())
                current.lastResumeAt = if (playing) System.currentTimeMillis() else 0
            }
        }
    }

    /** Called from Service.onDestroy; blocking here avoids losing the final seconds. */
    fun onShutdown() {
        stopTicker()
        runBlocking {
            mutex.withLock {
                playing = false
                flushLocked(System.currentTimeMillis())
                session = null
            }
        }
        scope.cancel()
    }

    // Internals --------------------------------------------------------

    private fun updateTicker() {
        if (playing) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MILLIS)
                mutex.withLock { flushLocked(System.currentTimeMillis()) }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Accrue wall-clock time since the last resume without touching the database. */
    private fun accrueLocked(now: Long) {
        val current = session ?: return
        if (current.lastResumeAt <= 0) return
        val delta = (now - current.lastResumeAt).coerceAtLeast(0)
        current.pendingMillis += delta
        current.accumulatedMillis += delta
        current.lastResumeAt = now
    }

    private fun playCountThresholdMillis(durationMillis: Long): Long {
        return (durationMillis / 2)
            .coerceAtMost(MIN_PLAY_MILLIS)
            .coerceAtLeast(1_000L)
    }

    private fun shouldCount(current: Session): Boolean {
        return !current.countedThisSession &&
            current.accumulatedMillis >= playCountThresholdMillis(current.durationMillis)
    }

    private suspend fun flushLocked(now: Long) {
        val current = session ?: return
        accrueLocked(now)

        val delta = current.pendingMillis
        val countsNow = shouldCount(current)
        if (delta < NOISE_FLOOR_MILLIS && !countsNow) return

        current.pendingMillis = 0
        if (countsNow) current.countedThisSession = true
        if (delta <= 0 && !countsNow) return

        val epochDay =
            Instant.ofEpochMilli(now).atZone(zoneId()).toLocalDate().toEpochDay().toInt()
        val increment = if (countsNow) 1 else 0

        try {
            db.withTransaction {
                db.dailyDao().accumulate(
                    trackRowId = current.trackRowId,
                    epochDay = epochDay,
                    playCount = increment,
                    playedMillis = delta,
                    at = now,
                )
                db.totalsDao().accumulate(
                    trackRowId = current.trackRowId,
                    count = increment,
                    millis = delta,
                    at = now,
                )
                if (countsNow) {
                    db.eventDao().insert(
                        PlayEventEntity(
                            trackRowId = current.trackRowId,
                            playedAt = now,
                            playedMillis = current.accumulatedMillis,
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            // Put the time back so a later flush can retry instead of silently losing it.
            current.pendingMillis += delta
            if (countsNow) current.countedThisSession = false
            Log.e("Phocid", "Unable to write playback statistics", ex)
        }
    }
}
