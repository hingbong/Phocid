package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class WidgetArtworkBudgetTest {
    private val displaySizes =
        listOf(
            1080 to 2400, // phone
            1440 to 3200, // phone, QHD+
            1812 to 2176, // foldable, unfolded
            2076 to 2152, // foldable, unfolded
            2400 to 1080, // phone, landscape
            1600 to 2560, // tablet
        )

    private val bytesPerPixelOptions =
        listOf(
            WidgetArtworkBudget.ARGB_8888_BYTES_PER_PIXEL,
            WidgetArtworkBudget.RGB_565_BYTES_PER_PIXEL,
        )

    @Test
    fun capMatchesThePlatformFormula() {
        assertThat(WidgetArtworkBudget.capBytes(1080, 2400)).isEqualTo(15_552_000L)
        assertThat(WidgetArtworkBudget.capBytes(1440, 3200)).isEqualTo(27_648_000L)
    }

    @Test
    fun artworkFitsTheCapOnEveryDisplay() {
        for ((width, height) in displaySizes) {
            for (bytesPerPixel in bytesPerPixelOptions) {
                val label = "${width}x$height at $bytesPerPixel bytes per pixel"
                val cap = WidgetArtworkBudget.capBytes(width, height)
                val side = WidgetArtworkBudget.maxArtworkSidePx(width, height, bytesPerPixel)
                val bytes = WidgetArtworkBudget.bitmapBytes(side, bytesPerPixel)
                assertThat(side)
                    .describedAs("side of $label")
                    .isGreaterThanOrEqualTo(WidgetArtworkBudget.MIN_ARTWORK_SIDE_PX)
                assertThat(bytes).describedAs("bytes of $label").isLessThanOrEqualTo(cap)
                assertThat(bytes * WidgetArtworkBudget.MAX_BITMAP_REFERENCES)
                    .describedAs("bytes of $label when the bitmap is referenced twice")
                    .isLessThanOrEqualTo(cap)
            }
        }
    }

    @Test
    fun artworkSideMatchesThePreviousEstimate() {
        // sqrt(6 * width * height / (4 bytes per pixel * 2 references)) is what the widget
        // computed inline before the budget became a pure function.
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(1080, 2400)).isEqualTo(1394)
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(1440, 3200)).isEqualTo(1859)
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(2076, 2152)).isEqualTo(1830)
    }

    @Test
    fun largerDisplaysAllowLargerArtwork() {
        val phone = WidgetArtworkBudget.maxArtworkSidePx(1080, 2400)
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(1440, 3200)).isGreaterThan(phone)
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(1600, 2560)).isGreaterThan(phone)
    }

    @Test
    fun rgb565AllowsLargerArtworkThanArgb8888() {
        val argb8888 = WidgetArtworkBudget.maxArtworkSidePx(1080, 2400)
        val rgb565 =
            WidgetArtworkBudget.maxArtworkSidePx(
                1080,
                2400,
                WidgetArtworkBudget.RGB_565_BYTES_PER_PIXEL,
            )
        assertThat(rgb565).isGreaterThan(argb8888)
    }

    @Test
    fun minimumSideIsKeptWhenItFits() {
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(300, 300))
            .isGreaterThanOrEqualTo(WidgetArtworkBudget.MIN_ARTWORK_SIDE_PX)
    }

    @Test
    fun minimumSideIsDroppedWhenItWouldOverflow() {
        // No real display is this small; the floor must never push artwork over the cap.
        val width = 200
        val height = 200
        val cap = WidgetArtworkBudget.capBytes(width, height)
        val side = WidgetArtworkBudget.maxArtworkSidePx(width, height)
        val bytesPerPixel = WidgetArtworkBudget.ARGB_8888_BYTES_PER_PIXEL
        val bytes = WidgetArtworkBudget.bitmapBytes(side, bytesPerPixel)
        assertThat(side).isLessThan(WidgetArtworkBudget.MIN_ARTWORK_SIDE_PX)
        assertThat(bytes).isLessThanOrEqualTo(cap)
        assertThat(bytes * WidgetArtworkBudget.MAX_BITMAP_REFERENCES).isLessThanOrEqualTo(cap)
    }

    @Test
    fun degenerateBoundsFallBackToTheMinimum() {
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(0, 2400))
            .isEqualTo(WidgetArtworkBudget.MIN_ARTWORK_SIDE_PX)
        assertThat(WidgetArtworkBudget.maxArtworkSidePx(1080, 0))
            .isEqualTo(WidgetArtworkBudget.MIN_ARTWORK_SIDE_PX)
    }
}
