package org.sunsetware.phocid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.sunsetware.phocid.data.PlaybackClock

/**
 * Pure JVM tests for [PlaybackClock.positionAt]. The clock is never subscribed to, so the ticker
 * never starts and no Android class is touched: [PlaybackClock.positionAt] is always called with an
 * explicit timestamp.
 */
class PlaybackClockTest {
    private lateinit var scope: CoroutineScope
    private var sample: PlaybackClock.Sample? = null

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob())
        sample = null
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun clock(): PlaybackClock = PlaybackClock(scope) { sample }

    private fun sampled(
        positionMs: Long = 1_000L,
        durationMs: Long = 0L,
        isPlaying: Boolean = true,
        speed: Float = 1f,
        sampledAtUptimeMs: Long = T0,
    ) =
        PlaybackClock.Sample(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            speed = speed,
            sampledAtUptimeMs = sampledAtUptimeMs,
        )

    /** Builds a clock whose snapshot already holds the given sample, as after an invalidate. */
    private fun clockSampling(sample: PlaybackClock.Sample?): PlaybackClock {
        this.sample = sample
        return clock().also { it.invalidate() }
    }

    @Test
    fun noSampleYetReturnsZero() {
        val clock = clock()
        assertThat(clock.positionAt(0L)).isZero()
        assertThat(clock.positionAt(T0 + 5_000L)).isZero()
    }

    @Test
    fun pausedSampleIsFrozen() {
        val clock = clockSampling(sampled(positionMs = 1_500L, isPlaying = false))
        assertThat(clock.positionAt(T0)).isEqualTo(1_500L)
        assertThat(clock.positionAt(T0 + 60_000L)).isEqualTo(1_500L)
    }

    @Test
    fun negativePausedPositionIsClampedToZero() {
        val clock = clockSampling(sampled(positionMs = -800L, isPlaying = false))
        assertThat(clock.positionAt(T0 + 60_000L)).isZero()
    }

    @Test
    fun playingSampleAdvancesWithTime() {
        val clock = clockSampling(sampled(positionMs = 1_000L, speed = 1f))
        assertThat(clock.positionAt(T0)).isEqualTo(1_000L)
        assertThat(clock.positionAt(T0 + 500L)).isEqualTo(1_500L)
    }

    @Test
    fun speedScalesTheAdvance() {
        val doubled = clockSampling(sampled(positionMs = 1_000L, speed = 2f))
        assertThat(doubled.positionAt(T0 + 500L)).isEqualTo(2_000L)

        val halved = clockSampling(sampled(positionMs = 1_000L, speed = 0.5f))
        assertThat(halved.positionAt(T0 + 500L)).isEqualTo(1_250L)
    }

    @Test
    fun advanceIsClampedToDuration() {
        val clock = clockSampling(sampled(positionMs = 1_000L, durationMs = 2_000L))
        assertThat(clock.positionAt(T0 + 5_000L)).isEqualTo(2_000L)
    }

    @Test
    fun unknownDurationSkipsTheUpperClamp() {
        val clock = clockSampling(sampled(positionMs = 1_000L, durationMs = 0L))
        assertThat(clock.positionAt(T0 + 5_000L)).isEqualTo(6_000L)
    }

    @Test
    fun staleTimestampDoesNotRewindThePosition() {
        val clock = clockSampling(sampled(positionMs = 1_000L, speed = 1f))
        assertThat(clock.positionAt(T0 - 1_000L)).isEqualTo(1_000L)
    }

    @Test
    fun invalidateTakesTheNewBaseImmediately() {
        val clock = clockSampling(sampled(positionMs = 1_000L, isPlaying = false))
        assertThat(clock.positionAt(T0)).isEqualTo(1_000L)

        // A paused seek re-samples at once instead of waiting for the next tick.
        sample = sampled(positionMs = 5_000L, isPlaying = false, sampledAtUptimeMs = T0 + 2_000L)
        clock.invalidate()
        assertThat(clock.positionAt(T0 + 2_000L)).isEqualTo(5_000L)
    }

    private companion object {
        const val T0 = 10_000L
    }
}
