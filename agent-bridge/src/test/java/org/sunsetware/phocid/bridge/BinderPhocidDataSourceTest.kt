package org.sunsetware.phocid.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sunsetware.phocid.agent.IPhocidQuery

class BinderPhocidDataSourceTest {
    @Test
    fun currentServiceAndGenerationAreAccepted() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val generation = retry.beginAttempt()!!
        assertTrue(retry.markBound(generation))
        val service = FakeQuery()

        assertTrue(isCurrentQuery(service, service, retry, generation))
    }

    @Test
    fun resultFromAReplacedServiceIsRejected() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val generation = retry.beginAttempt()!!
        assertTrue(retry.markBound(generation))
        val oldService = FakeQuery()
        val newService = FakeQuery()

        assertFalse(isCurrentQuery(newService, oldService, retry, generation))
    }

    @Test
    fun resultFromAnOlderGenerationIsRejected() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val first = retry.beginAttempt()!!
        assertTrue(retry.markBound(first))
        val service = FakeQuery()

        assertTrue(retry.markFailed(first))
        val second = retry.beginAttempt()!!
        assertTrue(retry.markBound(second))

        assertFalse(isCurrentQuery(service, service, retry, first))
        assertTrue(isCurrentQuery(service, service, retry, second))
    }

    @Test
    fun resultAfterFailoverIsRejected() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val generation = retry.beginAttempt()!!
        assertTrue(retry.markBound(generation))
        val service = FakeQuery()

        assertTrue(retry.markFailed(generation))

        assertFalse(isCurrentQuery(service, service, retry, generation))
    }

    @Test
    fun resultWithoutAConnectedServiceIsRejected() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val generation = retry.beginAttempt()!!
        assertTrue(retry.markBound(generation))
        val service = FakeQuery()

        assertFalse(isCurrentQuery(null, service, retry, generation))
        retry.close()
        assertFalse(isCurrentQuery(service, service, retry, generation))
    }

    private class FakeQuery : IPhocidQuery.Default()
}
