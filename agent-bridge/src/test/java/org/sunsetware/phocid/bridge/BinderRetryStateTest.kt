package org.sunsetware.phocid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderRetryStateTest {
    private var nowMs = 0L
    private val retry = BinderRetryState(cooldownMs = 1_000L) { nowMs }

    @Test
    fun concurrentCallersShareOneAttempt() {
        val generation = retry.beginAttempt()
        assertNotNull(generation)
        assertEquals(BinderConnectionState.BINDING, retry.state)
        assertNull(retry.beginAttempt())
    }

    @Test
    fun failedAttemptRetriesOnlyAfterCooldown() {
        val generation = retry.beginAttempt()!!
        assertTrue(retry.markFailed(generation))
        assertEquals(BinderConnectionState.FAILED, retry.state)

        nowMs = 999L
        assertFalse(retry.canBeginAttempt())
        assertNull(retry.beginAttempt())

        nowMs = 1_000L
        assertTrue(retry.canBeginAttempt())
        val next = retry.beginAttempt()
        assertNotNull(next)
        assertEquals(generation + 1, next)
    }

    @Test
    fun staleCallbackCannotReplaceNewAttempt() {
        val first = retry.beginAttempt()!!
        assertTrue(retry.markFailed(first))
        nowMs = 1_000L
        val second = retry.beginAttempt()!!
        assertEquals(BinderConnectionState.BINDING, retry.state)

        assertFalse(retry.markBound(first))
        assertEquals(BinderConnectionState.BINDING, retry.state)
        assertTrue(retry.markBound(second))
        assertEquals(BinderConnectionState.BOUND, retry.state)
    }

    @Test
    fun disconnectAllowsRetryAfterCooldown() {
        val generation = retry.beginAttempt()!!
        assertTrue(retry.markBound(generation))
        assertTrue(retry.markFailed(generation))
        assertEquals(BinderConnectionState.FAILED, retry.state)

        nowMs = 1_000L
        assertNotNull(retry.beginAttempt())
    }

    @Test
    fun closePreventsFutureBindsAndIgnoresStaleCallbacks() {
        val generation = retry.beginAttempt()!!
        retry.close()
        assertEquals(BinderConnectionState.CLOSED, retry.state)
        assertNull(retry.beginAttempt())
        assertFalse(retry.canBeginAttempt())
        assertFalse(retry.markBound(generation))
        assertFalse(retry.markFailed(generation))
    }
}
