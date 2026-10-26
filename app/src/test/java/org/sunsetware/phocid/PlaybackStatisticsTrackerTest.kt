package org.sunsetware.phocid

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.service.PlaybackStatisticsTracker

@RunWith(RobolectricTestRunner::class)
class PlaybackStatisticsTrackerTest {
    private var now = 0L
    private val plays = mutableListOf<Pair<Track, Long>>()
    private val times = mutableListOf<Pair<Track, Long>>()

    private fun track(duration: Duration = 20.seconds): Track {
        return Track(
            id = 1,
            path = "/music/song.flac",
            fileName = "song.flac",
            dateAdded = 0,
            version = 0,
            title = "Song",
            artists = listOf("Artist"),
            album = "Album",
            albumArtists = listOf("Album Artist"),
            genres = emptyList(),
            year = null,
            trackNumber = 1,
            discNumber = 1,
            duration = duration,
            size = 1000,
            format = "FLAC",
            sampleRate = 44100,
            bitRate = 1000,
            bitDepth = 16,
            hasArtwork = false,
            vibrantColor = null,
            mutedColor = null,
            unsyncedLyrics = null,
            comment = null,
        )
    }

    private fun tracker(): PlaybackStatisticsTracker {
        return PlaybackStatisticsTracker(
            clock = { now },
            onPlayCount = { track, playedMillis -> plays += track to playedMillis },
            onPlayedTime = { track, playedMillis -> times += track to playedMillis },
        )
    }

    @Test
    fun countsOnceAtHalfDurationAndAddsRemainder() {
        val track = track()
        val tracker = tracker()
        tracker.onTrackChanged(track)
        tracker.onPlayingChanged(true)

        now = 9_999
        tracker.tick()
        assertThat(plays).isEmpty()

        now = 10_000
        tracker.tick()
        assertThat(plays).hasSize(1)
        assertThat(plays.single().second).isEqualTo(10_000L)

        now = 15_000
        tracker.tick()
        tracker.finish()
        assertThat(plays).hasSize(1)
        assertThat(times).containsExactly(track to 5_000L)
    }

    @Test
    fun pauseAndResumeKeepTheSameSession() {
        val track = track()
        val tracker = tracker()
        tracker.onTrackChanged(track)
        tracker.onPlayingChanged(true)

        now = 6_000
        tracker.onPlayingChanged(false)
        now = 100_000
        tracker.onPlayingChanged(true)
        now = 104_000
        tracker.tick()
        tracker.finish()

        assertThat(plays).hasSize(1)
        assertThat(plays.single().second).isEqualTo(10_000L)
        assertThat(times).isEmpty()
    }

    @Test
    fun transitionBeforeThresholdDoesNotCount() {
        val firstTrack = track()
        val secondTrack = track().copy(id = 2, path = "/music/other.flac", fileName = "other.flac")
        val tracker = tracker()
        tracker.onTrackChanged(firstTrack)
        tracker.onPlayingChanged(true)

        now = 5_000
        tracker.onTrackChanged(secondTrack)
        tracker.finish()

        assertThat(plays).isEmpty()
        assertThat(times).isEmpty()
    }

    @Test
    fun loopingStartsANewSession() {
        val track = track()
        val tracker = tracker()
        tracker.onTrackChanged(track)
        tracker.onPlayingChanged(true)

        now = 10_000
        tracker.tick()
        now = 15_000
        tracker.onTrackChanged(track)
        now = 25_000
        tracker.tick()
        tracker.finish()

        assertThat(plays).hasSize(2)
        assertThat(times).containsExactly(track to 5_000L)
    }

    @Test
    fun longTracksUseTheFourMinuteCap() {
        val track = track(duration = 3600.seconds)
        val tracker = tracker()
        tracker.onTrackChanged(track)
        tracker.onPlayingChanged(true)

        now = 239_999
        tracker.tick()
        assertThat(plays).isEmpty()

        now = 240_000
        tracker.tick()
        assertThat(plays).hasSize(1)
    }
}
