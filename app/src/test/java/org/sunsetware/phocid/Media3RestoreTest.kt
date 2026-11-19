package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.data.computeRestoredSeek

class Media3RestoreTest {
    @Test
    fun capturedItemSurvivingKeepsItsIndexAndPosition() {
        val result = computeRestoredSeek(listOf(0, 1, 2, 3), capturedIndex = 2)

        assertThat(result.first).isEqualTo(2)
        assertThat(result.second).isTrue()
    }

    @Test
    fun droppedItemsBeforeTheCapturedOneShiftTheIndex() {
        // The queue was [a, b, c], b (index 1) was current, and a was deleted since the state
        // was captured. b survived, but reusing the captured index would land on c.
        val result = computeRestoredSeek(listOf(1, 2), capturedIndex = 1)

        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).isTrue()
    }

    @Test
    fun droppedCapturedItemFallsForwardToTheNextSurvivor() {
        // The queue was [a, b, c], b (index 1) was current and deleted since.
        val result = computeRestoredSeek(listOf(0, 2), capturedIndex = 1)

        assertThat(result.first).isEqualTo(1)
        assertThat(result.second).isFalse()
    }

    @Test
    fun droppedCapturedItemAtTheEndFallsBackToThePreviousSurvivor() {
        val result = computeRestoredSeek(listOf(0, 1), capturedIndex = 2)

        assertThat(result.first).isEqualTo(1)
        assertThat(result.second).isFalse()
    }

    @Test
    fun capturedIndexBeyondTheQueueFallsBackToTheLastSurvivor() {
        val result = computeRestoredSeek(listOf(0, 1), capturedIndex = 5)

        assertThat(result.first).isEqualTo(1)
        assertThat(result.second).isFalse()
    }

    @Test
    fun emptyQueueRestoresToIndexZero() {
        val result = computeRestoredSeek(emptyList(), capturedIndex = 3)

        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).isFalse()
    }
}
