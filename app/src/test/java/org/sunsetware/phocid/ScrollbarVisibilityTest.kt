package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.ui.components.adjustThumbOffsets
import org.sunsetware.phocid.ui.components.scrollbarShouldShow

/**
 * Tests the visibility predicate that the event-driven scrollbar fade observes via snapshotFlow
 * (previously evaluated by a ~17ms polling loop). Robolectric is required because loading the
 * scrollbar file initializes PathInterpolator-backed animation specs.
 */
@RunWith(RobolectricTestRunner::class)
class ScrollbarVisibilityTest {
    private val height = 2000f
    private val density = 3f

    @Test
    fun contentFittingViewport_neverShows() {
        val (start, end) = adjustThumbOffsets(0f, 1f, height, density)
        assertThat(
                scrollbarShouldShow(start, end, isScrollInProgress = true, isThumbDragging = false)
            )
            .isFalse()
        assertThat(
                scrollbarShouldShow(start, end, isScrollInProgress = false, isThumbDragging = true)
            )
            .isFalse()
    }

    @Test
    fun idleList_staysHidden() {
        // At top, middle and bottom of an overflowing list, no scroll and no drag means hidden.
        for ((rawStart, rawEnd) in listOf(0f to 0.2f, 0.4f to 0.6f, 0.8f to 1f)) {
            val (start, end) = adjustThumbOffsets(rawStart, rawEnd, height, density)
            assertThat(
                    scrollbarShouldShow(
                        start,
                        end,
                        isScrollInProgress = false,
                        isThumbDragging = false,
                    )
                )
                .describedAs("thumb [$rawStart, $rawEnd] while idle")
                .isFalse()
        }
    }

    @Test
    fun scrolling_showsAtTopMiddleAndBottom() {
        for ((rawStart, rawEnd) in listOf(0f to 0.2f, 0.4f to 0.6f, 0.8f to 1f)) {
            val (start, end) = adjustThumbOffsets(rawStart, rawEnd, height, density)
            assertThat(
                    scrollbarShouldShow(
                        start,
                        end,
                        isScrollInProgress = true,
                        isThumbDragging = false,
                    )
                )
                .describedAs("thumb [$rawStart, $rawEnd] while scrolling")
                .isTrue()
        }
    }

    @Test
    fun thumbDrag_showsEvenWithoutScrollInProgress() {
        // requestScrollToItem during a thumb drag does not necessarily set isScrollInProgress,
        // so the drag flag alone must keep the scrollbar visible.
        val (start, end) = adjustThumbOffsets(0f, 0.2f, height, density)
        assertThat(
                scrollbarShouldShow(start, end, isScrollInProgress = false, isThumbDragging = true)
            )
            .isTrue()
    }

    @Test
    fun minimumSizeThumb_stillTriggersVisibility() {
        // A tiny raw thumb is expanded to the minimum size; the derived range must still
        // satisfy the predicate at both ends of the list.
        val (topStart, topEnd) = adjustThumbOffsets(0f, 0.01f, height, density)
        assertThat(topStart).isEqualTo(0f)
        assertThat(topEnd).isGreaterThan(0.01f).isLessThan(1f)
        assertThat(
                scrollbarShouldShow(
                    topStart,
                    topEnd,
                    isScrollInProgress = true,
                    isThumbDragging = false,
                )
            )
            .isTrue()

        val (bottomStart, bottomEnd) = adjustThumbOffsets(0.99f, 1f, height, density)
        assertThat(bottomEnd).isEqualTo(1f)
        assertThat(bottomStart).isGreaterThan(0f)
        assertThat(
                scrollbarShouldShow(
                    bottomStart,
                    bottomEnd,
                    isScrollInProgress = true,
                    isThumbDragging = false,
                )
            )
            .isTrue()
    }
}
