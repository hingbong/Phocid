package org.sunsetware.phocid.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class StatsPageProgressTest {
    private val window = 200

    @Test
    fun windowLimitStopsPagingButIsStillReported() {
        val progress =
            statsPageProgress(
                totalTracks = 398,
                offset = 150,
                returned = 50,
                window = window,
                byteTruncated = false,
            )

        assertThat(progress.hasMore).isFalse()
        assertThat(progress.truncatedBy).isEqualTo("window")
    }

    @Test
    fun offsetPastTheWindowDoesNotSendClientsIntoAnEmptyTail() {
        val progress =
            statsPageProgress(
                totalTracks = 398,
                offset = 200,
                returned = 0,
                window = window,
                byteTruncated = false,
            )

        assertThat(progress.hasMore).isFalse()
        assertThat(progress.truncatedBy).isEqualTo("window")
    }

    @Test
    fun exactWindowSizeIsNotTruncated() {
        val progress =
            statsPageProgress(
                totalTracks = 200,
                offset = 0,
                returned = 200,
                window = window,
                byteTruncated = false,
            )

        assertThat(progress.hasMore).isFalse()
        assertThat(progress.truncatedBy).isNull()
    }

    @Test
    fun requestedLimitStillHasMore() {
        val progress =
            statsPageProgress(
                totalTracks = 100,
                offset = 0,
                returned = 20,
                window = window,
                byteTruncated = false,
            )

        assertThat(progress.hasMore).isTrue()
        assertThat(progress.truncatedBy).isEqualTo("limit")
    }

    @Test
    fun responseBudgetTakesPrecedence() {
        val progress =
            statsPageProgress(
                totalTracks = 100,
                offset = 0,
                returned = 20,
                window = window,
                byteTruncated = true,
            )

        assertThat(progress.hasMore).isTrue()
        assertThat(progress.truncatedBy).isEqualTo("budget")
    }

    @Test
    fun exhaustedRangeHasNoMoreAndNoTruncation() {
        val progress =
            statsPageProgress(
                totalTracks = 100,
                offset = 80,
                returned = 20,
                window = window,
                byteTruncated = false,
            )

        assertThat(progress.hasMore).isFalse()
        assertThat(progress.truncatedBy).isNull()
    }
}
