package org.sunsetware.phocid.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Application-side playback position clock.
 *
 * One coroutine samples the player while [position] has subscribers and publishes the result at
 * [PLAYING_INTERVAL_MS] while playing (and [PAUSED_INTERVAL_MS] while paused). Consumers that need
 * an arbitrary instant call [positionAt], which only reads the in-memory snapshot.
 */
class PlaybackClock(
    private val scope: CoroutineScope,
    private val sample: () -> Sample?,
) {
    /** A position reading captured at [sampledAtUptimeMs]. */
    data class Sample(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val speed: Float,
        val sampledAtUptimeMs: Long,
    )

    @Volatile private var snapshot: Sample? = null

    /**
     * Coarse position in milliseconds. Published at 10 Hz while playing and 1 Hz while paused, and
     * only sampled while there is at least one subscriber, so no position polling happens while no
     * screen is watching.
     */
    val position: StateFlow<Long> =
        flow {
                while (true) {
                    val current = refresh()
                    emit(current?.positionMs ?: 0L)
                    delay(
                        if (current?.isPlaying == true) PLAYING_INTERVAL_MS
                        else PAUSED_INTERVAL_MS
                    )
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /**
     * Position in milliseconds at [nowUptimeMs], extrapolated from the last sample without touching
     * the player. Callers on a frame clock should pass the frame time.
     */
    fun positionAt(nowUptimeMs: Long = SystemClock.uptimeMillis()): Long {
        val current = snapshot ?: return 0L
        if (!current.isPlaying) return current.positionMs.coerceAtLeast(0L)
        val elapsedMs = (nowUptimeMs - current.sampledAtUptimeMs).coerceAtLeast(0L)
        val extrapolatedMs = current.positionMs + (elapsedMs * current.speed).toLong()
        return if (current.durationMs > 0L) {
            extrapolatedMs.coerceIn(0L, current.durationMs)
        } else {
            extrapolatedMs.coerceAtLeast(0L)
        }
    }

    /** Re-samples immediately so seek, pause/resume, track change and speed change are instant. */
    fun invalidate() {
        refresh()
    }

    private fun refresh(): Sample? = sample()?.also { snapshot = it }

    companion object {
        /** 10 Hz is enough for a duration bar and far below the display refresh rate. */
        const val PLAYING_INTERVAL_MS = 100L

        /** While paused the position cannot change on its own, so 1 Hz is plenty. */
        const val PAUSED_INTERVAL_MS = 1_000L

        /** Survive a short recomposition gap without restarting the ticker. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
