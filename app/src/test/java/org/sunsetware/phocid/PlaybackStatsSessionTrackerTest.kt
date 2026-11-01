package org.sunsetware.phocid

import androidx.media3.common.Player
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PlaybackStatsSessionTrackerTest {
    private val tracker = PlaybackStatsSessionTracker()

    @Test
    fun playlistChangeForTheActiveItemDoesNotStartAnotherSession() {
        tracker.onStarted("track-a")

        val reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        assertThat(tracker.shouldStart("track-a", reason)).isFalse()
    }

    @Test
    fun automaticAdvanceStartsTheNextSessionAndClosesThePreviousAsEnded() {
        tracker.onStarted("track-a")

        val reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        assertThat(tracker.shouldStart("track-b", reason)).isTrue()
        assertThat(tracker.previousEndedByPlayer(reason)).isTrue()
    }

    @Test
    fun seekToAnotherItemStartsANewSessionWithoutCompletingThePreviousOne() {
        tracker.onStarted("track-a")

        val reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
        assertThat(tracker.shouldStart("track-b", reason)).isTrue()
        assertThat(tracker.previousEndedByPlayer(reason)).isFalse()
    }

    @Test
    fun repeatStartsANewSessionForTheSameItem() {
        tracker.onStarted("track-a")

        val reason = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        assertThat(tracker.shouldStart("track-a", reason)).isTrue()
        assertThat(tracker.previousEndedByPlayer(reason)).isTrue()
    }

    @Test
    fun playlistChangeAfterThePlayerEndedDoesNotOpenTheNextSession() {
        tracker.onStarted("track-a")
        tracker.onFinished()

        val reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        assertThat(tracker.shouldStart("track-a", reason)).isFalse()
    }

    @Test
    fun replayAfterThePlayerEndedOpensANewSession() {
        tracker.onStarted("track-a")
        tracker.onFinished()

        assertThat(tracker.shouldStart("track-a", transitionReason = null)).isTrue()
        assertThat(tracker.previousEndedByPlayer(transitionReason = null)).isFalse()
    }

    @Test
    fun duplicateItemOnAutomaticAdvanceStartsANewSession() {
        tracker.onStarted("track-a")

        val reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        assertThat(tracker.shouldStart("track-a", reason)).isTrue()
        assertThat(tracker.previousEndedByPlayer(reason)).isTrue()
    }
}
