package org.sunsetware.phocid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.ui.components.MultiSelectState
import org.sunsetware.phocid.ui.views.library.activeTabTypesFor
import org.sunsetware.phocid.ui.views.library.gateTabItems
import org.sunsetware.phocid.utils.combine
import org.sunsetware.phocid.utils.map

@RunWith(RobolectricTestRunner::class)
class LibraryScreenHomeViewStateTest {
    @Test
    fun settledPagerKeepsOnlyTheVisibleTabActive() {
        val preferences = Preferences()
        assertThat(activeTabTypesFor(0, 0, preferences)).containsExactly(preferences.tabs[0].type)
        val last = preferences.tabs.size - 1
        assertThat(activeTabTypesFor(last, last, preferences))
            .containsExactly(preferences.tabs.last().type)
    }

    @Test
    fun transitionKeepsBothDepartingAndTargetTabsActive() {
        val preferences = Preferences()
        // During animateScrollToPage, targetPage flips immediately but currentPage (still fully
        // visible) only follows once the settle completes: both tab types must stay active.
        assertThat(activeTabTypesFor(0, 2, preferences))
            .containsExactlyInAnyOrder(preferences.tabs[0].type, preferences.tabs[2].type)
        assertThat(activeTabTypesFor(0, 2, preferences)).doesNotContain(preferences.tabs[1].type)
    }

    @Test
    fun outOfRangePageHasNoActiveTab() {
        val preferences = Preferences()
        // A transient pager position outside the tab list must gate every tab off instead of
        // crashing: nothing computes while the position settles.
        assertThat(activeTabTypesFor(preferences.tabs.size, preferences.tabs.size, preferences))
            .isEmpty()
        assertThat(activeTabTypesFor(-1, -1, preferences)).isEmpty()
        // An out-of-range target must not take the still-visible current page down with it.
        assertThat(activeTabTypesFor(0, preferences.tabs.size, preferences))
            .containsExactly(preferences.tabs[0].type)
    }

    /**
     * Regression test for the tab-switch blank-out: gating on targetPage alone handed the departing
     * page an empty list (wiping its multi-selection) while it was still visible during the settle
     * animation. Both transition pages must keep their items; a third, invisible tab must not
     * compute at all.
     *
     * Flows are shared into a dedicated cancelled [CoroutineScope], never the runBlocking scope
     * (see StateFlowUtilsTest): stateIn's sharing coroutine would otherwise hang runBlocking.
     */
    @Test
    fun departingPageKeepsItemsAndSelectionUntilSettleCompletes() =
        runBlocking<Unit> {
            val scope = CoroutineScope(Job())
            try {
                val preferences = Preferences()
                val departingType = preferences.tabs[0].type
                val invisibleType = preferences.tabs[1].type
                val targetType = preferences.tabs[2].type

                // Stand-in for snapshotFlow { currentPage to targetPage }: mid-transition.
                val pages = MutableStateFlow(0 to 2)
                val activeTabTypes =
                    pages.combine(scope, MutableStateFlow(preferences)) { (current, target), prefs
                        ->
                        activeTabTypesFor(current, target, prefs)
                    }

                var departingTransforms = 0
                var invisibleTransforms = 0
                var targetTransforms = 0
                val departingSource = MutableStateFlow(listOf("departing"))
                val invisibleSource = MutableStateFlow(listOf("invisible"))
                val targetSource = MutableStateFlow(listOf("target"))
                val departingItems =
                    activeTabTypes.gateTabItems(
                        departingType,
                        scope,
                        departingSource.map(scope) {
                            departingTransforms++
                            it
                        },
                    )
                val invisibleItems =
                    activeTabTypes.gateTabItems(
                        invisibleType,
                        scope,
                        invisibleSource.map(scope) {
                            invisibleTransforms++
                            it
                        },
                    )
                val targetItems =
                    activeTabTypes.gateTabItems(
                        targetType,
                        scope,
                        targetSource.map(scope) {
                            targetTransforms++
                            it
                        },
                    )

                // Keep all three gated flows subscribed, like the pager's pages do.
                listOf(departingItems, invisibleItems, targetItems).forEach { flow ->
                    scope.launch { flow.collect() }
                }
                val selection = MultiSelectState(scope, departingItems)
                try {
                    withTimeout(5_000) {
                        departingItems.first { it == listOf("departing") }
                        targetItems.first { it == listOf("target") }
                    }

                    // Mid-transition: the departing page keeps its items and its selection,
                    // the target page is already hot, and the invisible third tab is empty.
                    withTimeout(5_000) { selection.items.first { it.size == 1 } }
                    selection.toggleSelect(0)
                    assertThat(selection.items.value.selection).containsExactly("departing")
                    assertThat(invisibleItems.value).isEmpty()
                    val invisibleTransformsWhileCold = invisibleTransforms
                    invisibleSource.value = listOf("invisible-changed")
                    targetSource.value = listOf("target-changed")
                    withTimeout(5_000) { targetItems.first { it == listOf("target-changed") } }
                    assertThat(targetTransforms).isGreaterThanOrEqualTo(2)
                    assertThat(departingItems.value).isEqualTo(listOf("departing"))
                    assertThat(selection.items.value.selection).containsExactly("departing")
                    // The invisible tab must not compute while it is not visible.
                    assertThat(invisibleItems.value).isEmpty()
                    assertThat(invisibleTransforms).isEqualTo(invisibleTransformsWhileCold)

                    // Settle completes: currentPage catches up with targetPage. Only the
                    // settled tab stays hot; the departing tab now goes cold (its upstream
                    // may linger for the WhileSubscribed stop timeout, but nothing reaches
                    // the page anymore).
                    pages.value = 2 to 2
                    withTimeout(5_000) { departingItems.first { it.isEmpty() } }
                    assertThat(targetItems.value).isEqualTo(listOf("target-changed"))
                    departingSource.value = listOf("departing-changed")
                    targetSource.value = listOf("target-settled")
                    withTimeout(5_000) { targetItems.first { it == listOf("target-settled") } }
                    assertThat(departingItems.value).isEmpty()
                } finally {
                    selection.close()
                }
            } finally {
                scope.cancel()
            }
        }
}
