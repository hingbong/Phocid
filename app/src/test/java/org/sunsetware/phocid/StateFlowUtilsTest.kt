package org.sunsetware.phocid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.utils.combine
import org.sunsetware.phocid.utils.flatMapLatest
import org.sunsetware.phocid.utils.map

/**
 * These helpers are stateIn(WhileSubscribed) wrappers, so the interesting property is what they do
 * while nobody collects: construction may run one eager transform, but nothing else may compute
 * until a subscriber arrives.
 *
 * Each test shares its flows into a dedicated [CoroutineScope] that is cancelled in a finally
 * block, never into the runBlocking scope itself. stateIn launches a long-lived sharing coroutine
 * in the scope it is given; if that scope is runBlocking's, runBlocking joins the sharing coroutine
 * on exit and hangs forever (WhileSubscribed stops the upstream but the sharing coroutine itself
 * never completes). A standalone Job() scope keeps those coroutines out of runBlocking's child
 * hierarchy, and cancelling it in finally releases them even when an assertion throws. first{} is
 * used where a subscription is genuinely needed because it unsubscribes by itself.
 */
class StateFlowUtilsTest {
    @Test
    fun combineWithAnInitialValueSkipsTheEagerTransform() =
        runBlocking<Unit> {
            val scope = CoroutineScope(Job())
            try {
                val a = MutableStateFlow(0)
                val b = MutableStateFlow(0)
                val c = MutableStateFlow(0)
                var transforms = 0
                val flow =
                    a.combine(scope, b, c, initial = -1) { x, y, z ->
                        transforms++
                        x + y + z
                    }

                // Construction must not run the transform: the placeholder stays the value until
                // the
                // first subscriber arrives, so nothing computes on the caller's thread.
                assertThat(flow.value).isEqualTo(-1)
                assertThat(transforms).isZero()

                // A subscription is what starts the WhileSubscribed upstream. first{} takes the
                // emitted
                // value and then unsubscribes, so the placeholder is replaced and the transform
                // runs.
                withTimeout(5_000) { flow.first { it == 0 } }
                assertThat(transforms).isEqualTo(1)

                a.value = 5
                withTimeout(5_000) { flow.first { it == 5 } }
                assertThat(transforms).isGreaterThanOrEqualTo(2)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun combineWithoutInitialStillComputesEagerly() =
        runBlocking<Unit> {
            val scope = CoroutineScope(Job())
            try {
                val a = MutableStateFlow(1)
                val b = MutableStateFlow(2)
                val c = MutableStateFlow(3)
                var transforms = 0
                val flow =
                    a.combine(scope, b, c) { x, y, z ->
                        transforms++
                        x + y + z
                    }

                assertThat(flow.value).isEqualTo(6)
                assertThat(transforms).isEqualTo(1)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun flatMapLatestDoesNotCollectUntilSubscribed() =
        runBlocking<Unit> {
            val scope = CoroutineScope(Job())
            try {
                val page = MutableStateFlow("a")
                var transformsA = 0
                var transformsB = 0
                // Each map performs exactly one eager transform at construction time.
                val innerA =
                    MutableStateFlow(1).map(scope) {
                        transformsA++
                        it
                    }
                val innerB =
                    MutableStateFlow(2).map(scope) {
                        transformsB++
                        it
                    }
                val gated =
                    page.flatMapLatest(scope) { active -> if (active == "a") innerA else innerB }

                assertThat(transformsA).isEqualTo(1)
                assertThat(transformsB).isEqualTo(1)
                assertThat(gated.value).isEqualTo(1)

                // No subscriber exists, so the WhileSubscribed upstream is stopped: moving the
                // pager
                // must not recompute anything and must not change the shared value. This is the
                // property that keeps an invisible library tab from doing work.
                page.value = "b"
                assertThat(gated.value).isEqualTo(1)
                assertThat(transformsA).isEqualTo(1)
                assertThat(transformsB).isEqualTo(1)
            } finally {
                scope.cancel()
            }
        }
}
