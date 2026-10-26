package org.sunsetware.phocid.service

import android.os.SystemClock
import org.sunsetware.phocid.data.Track

private const val PLAY_COUNT_THRESHOLD_CAP_MILLIS = 4 * 60 * 1000L
private const val PLAY_COUNT_THRESHOLD_MIN_MILLIS = 1000L

/**
 * Tracks actual listening time for the current playback session.
 *
 * A play is counted once the session reaches half of the track duration, capped at four minutes,
 * with a one-second minimum. Pausing and resuming continue the same session; changing the media
 * item starts a new one.
 */
class PlaybackStatisticsTracker(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val onPlayCount: (Track, Long) -> Unit,
    private val onPlayedTime: (Track, Long) -> Unit,
) {
    private var currentTrack: Track? = null
    private var accumulatedMillis = 0L
    private var lastResumeAt: Long? = null
    private var isPlaying = false
    private var counted = false
    private var reportedMillis = 0L

    fun onTrackChanged(track: Track?) {
        tick()
        finalizeSession()
        currentTrack = track
        accumulatedMillis = 0
        lastResumeAt = if (isPlaying) clock() else null
        counted = false
        reportedMillis = 0
    }

    fun onPlayingChanged(playing: Boolean) {
        tick()
        isPlaying = playing
        lastResumeAt = if (playing) clock() else null
    }

    fun tick() {
        val track = currentTrack ?: return
        val last = lastResumeAt ?: return
        if (!isPlaying) return

        val now = clock()
        val delta = (now - last).coerceAtLeast(0)
        lastResumeAt = now
        accumulatedMillis = addClamped(accumulatedMillis, delta)

        if (!counted && accumulatedMillis >= playCountThresholdMillis(track)) {
            counted = true
            reportedMillis = accumulatedMillis
            onPlayCount(track, accumulatedMillis)
        }
    }

    fun finish() {
        tick()
        finalizeSession()
        currentTrack = null
        accumulatedMillis = 0
        lastResumeAt = null
        isPlaying = false
        counted = false
        reportedMillis = 0
    }

    private fun finalizeSession() {
        val track = currentTrack ?: return
        val played = accumulatedMillis
        if (played <= 0) return

        if (counted) {
            val remaining = (played - reportedMillis).coerceAtLeast(0)
            if (remaining > 0) onPlayedTime(track, remaining)
        } else if (played >= playCountThresholdMillis(track)) {
            counted = true
            reportedMillis = played
            onPlayCount(track, played)
        }
    }

    private fun playCountThresholdMillis(track: Track): Long {
        return (track.duration.inWholeMilliseconds / 2)
            .coerceAtMost(PLAY_COUNT_THRESHOLD_CAP_MILLIS)
            .coerceAtLeast(PLAY_COUNT_THRESHOLD_MIN_MILLIS)
    }

    private fun addClamped(a: Long, b: Long): Long {
        val value = a + b
        return if (value < 0) Long.MAX_VALUE else value
    }
}
