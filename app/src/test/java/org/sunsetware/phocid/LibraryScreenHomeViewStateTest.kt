package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.ui.views.library.LibraryScreenTabType
import org.sunsetware.phocid.ui.views.library.activeTabTypeFor

@RunWith(RobolectricTestRunner::class)
class LibraryScreenHomeViewStateTest {
    @Test
    fun activeTabTypeFollowsTheVisiblePage() {
        val preferences = Preferences()
        assertThat(activeTabTypeFor(0, preferences)).isEqualTo(preferences.tabs[0].type)
        assertThat(activeTabTypeFor(preferences.tabs.size - 1, preferences))
            .isEqualTo(preferences.tabs.last().type)
    }

    @Test
    fun outOfRangePageHasNoActiveTab() {
        val preferences = Preferences()
        // A transient pager position outside the tab list must gate every tab off instead of
        // crashing: nothing computes while the position settles.
        assertThat(activeTabTypeFor(preferences.tabs.size, preferences)).isNull()
        assertThat(activeTabTypeFor(-1, preferences)).isNull()
    }
}
