package org.sunsetware.phocid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.utils.combine
import org.sunsetware.phocid.utils.flatMapLatest
import org.sunsetware.phocid.utils.map

/**
 * These tests never leave a collector running in the background: runBlocking joins every child
 * coroutine it sees, so a launched collect that is not cancelled on the failure path would hang
 * the test (and the test JVM) forever. Subscription is instead triggered by first{}, which
 * unsubscribes by itself, or by a bounded observation window that cancels on timeout.
 */
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

        // A subscription is what starts the WhileSubscribed upstream. first{} takes the emitted
        // value and then unsubscribes, so the placeholder is replaced and the transform runs.
        withTimeout(5_000) { flow.first { it == 0 } }
        assertThat(transforms).isEqualTo(1)

        a.value = 5
        withTimeout(5_000) { flow.first { it == 5 } }
        assertThat(transforms).isGreaterThanOrEqualTo(2)
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
        // The shared value is already 1, so first { it == 1 } would return without ever
        // subscribing. Wait instead for a value that never arrives: the upstream stays collected
        // for the bounded window and is cancelled on timeout, which is what we want to observe.
        withTimeoutOrNull(2_000) { gated.first { it == Int.MIN_VALUE } }
        // Only the active tab's flow has been computed since construction.
        assertThat(transformsA).isGreaterThanOrEqualTo(2)
        assertThat(transformsB).isEqualTo(1)

        page.value = "b"
        withTimeoutOrNull(2_000) { gated.first { it == Int.MIN_VALUE } }
        assertThat(transformsB).isGreaterThanOrEqualTo(2)
    }
}
