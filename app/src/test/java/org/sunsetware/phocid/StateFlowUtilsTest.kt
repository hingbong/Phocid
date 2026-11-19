package org.sunsetware.phocid

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.utils.combine
import org.sunsetware.phocid.utils.flatMapLatest
import org.sunsetware.phocid.utils.map

class StateFlowUtilsTest {
    @Test
    fun combineWithAnInitialValueSkipsTheEagerTransform() = runBlocking<Unit> {
        val a = MutableStateFlow(0)
        val b = MutableStateFlow(0)
        val c = MutableStateFlow(0)
        var transforms = 0
        val flow =
            a.combine(this, b, c, initial = -1) { x, y, z ->
                transforms++
                x + y + z
            }

        // Construction must not run the transform: the placeholder stays the value until the
        // first subscriber arrives, so nothing computes on the caller's thread.
        assertThat(flow.value).isEqualTo(-1)
        assertThat(transforms).isZero()

        // A real subscription is required: StateFlow.first() would hand back the cached
        // placeholder (-1) without ever starting the WhileSubscribed upstream.
        val subscription = launch { flow.collect {} }
        withTimeout(5_000) { flow.first { it == 0 } }
        assertThat(transforms).isEqualTo(1)

        a.value = 5
        withTimeout(5_000) { flow.first { it == 5 } }
        assertThat(transforms).isGreaterThanOrEqualTo(2)
        subscription.cancel()
    }

    @Test
    fun combineWithoutInitialStillComputesEagerly() = runBlocking<Unit> {
        val a = MutableStateFlow(1)
        val b = MutableStateFlow(2)
        val c = MutableStateFlow(3)
        var transforms = 0
        val flow = a.combine(this, b, c) { x, y, z -> transforms++; x + y + z }

        assertThat(flow.value).isEqualTo(6)
        assertThat(transforms).isEqualTo(1)
    }

    @Test
    fun flatMapLatestSubscribesOnlyTheActiveInnerFlow() = runBlocking<Unit> {
        val page = MutableStateFlow("a")
        var transformsA = 0
        var transformsB = 0
        // Each map call performs one eager initial transform; further transforms only happen
        // while the inner flow is actually subscribed.
        val innerA = MutableStateFlow(1).map(this) { transformsA++; it }
        val innerB = MutableStateFlow(2).map(this) { transformsB++; it }
        val gated =
            page.flatMapLatest(this) { active -> if (active == "a") innerA else innerB }

        assertThat(gated.value).isEqualTo(1)
        // Subscribe so the flatMapLatest upstream actually collects the active inner flow, then
        // yield until it has done so. Polling is required: the shared value is already 1, so a
        // first { it == 1 } would return immediately and never let the subscription start.
        val subscription = launch { gated.collect {} }
        withTimeout(5_000) { while (transformsA < 2) delay(10) }
        // Only the active tab's flow has been computed since construction.
        assertThat(transformsA).isGreaterThanOrEqualTo(2)
        assertThat(transformsB).isEqualTo(1)

        page.value = "b"
        withTimeout(5_000) { while (transformsB < 2) delay(10) }
        assertThat(transformsB).isGreaterThanOrEqualTo(2)
        subscription.cancel()
    }
}