package org.sunsetware.phocid.bridge

import org.junit.Assert.assertEquals
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

    @Test
    fun api3DecisionFromAReplacedSessionDoesNotRejectApi4Call() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val oldGeneration = retry.beginAttempt()!!
        assertTrue(retry.markBound(oldGeneration))
        val api3Service = FakeQuery()

        // The API3 session correctly rejects updateSortKeys (requiredApi = 4)...
        assertEquals(
            ApiDecision.UNSUPPORTED,
            decideApiVersion(4, 3, api3Service, oldGeneration, api3Service, retry),
        )

        // ...but an API4 session replaces it while that decision is in flight.
        assertTrue(retry.markFailed(oldGeneration))
        val newGeneration = retry.beginAttempt()!!
        assertTrue(retry.markBound(newGeneration))
        val api4Service = FakeQuery()

        // The old API3 answer must be re-evaluated instead of becoming unsupported_api.
        assertFalse(isQueriedVersionAuthoritative(api3Service, oldGeneration, api4Service, retry))
        assertEquals(
            ApiDecision.STALE_SESSION,
            decideApiVersion(4, 3, api3Service, oldGeneration, api4Service, retry),
        )
        assertTrue(isQueriedVersionAuthoritative(api4Service, newGeneration, api4Service, retry))
        assertEquals(
            ApiDecision.ALLOWED,
            decideApiVersion(4, 4, api4Service, newGeneration, api4Service, retry),
        )
    }

    @Test
    fun api4DecisionFromAReplacedSessionDoesNotAuthorizeApi3Call() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val oldGeneration = retry.beginAttempt()!!
        assertTrue(retry.markBound(oldGeneration))
        val api4Service = FakeQuery()

        // The API4 session correctly allows updateSortKeys (requiredApi = 4)...
        assertEquals(
            ApiDecision.ALLOWED,
            decideApiVersion(4, 4, api4Service, oldGeneration, api4Service, retry),
        )

        // ...but an API3 session replaces it while that decision is in flight.
        assertTrue(retry.markFailed(oldGeneration))
        val newGeneration = retry.beginAttempt()!!
        assertTrue(retry.markBound(newGeneration))
        val api3Service = FakeQuery()

        // The old API4 answer must not authorize a method the current service lacks.
        assertFalse(isQueriedVersionAuthoritative(api4Service, oldGeneration, api3Service, retry))
        assertEquals(
            ApiDecision.STALE_SESSION,
            decideApiVersion(4, 4, api4Service, oldGeneration, api3Service, retry),
        )
        assertTrue(isQueriedVersionAuthoritative(api3Service, newGeneration, api3Service, retry))
        assertEquals(
            ApiDecision.UNSUPPORTED,
            decideApiVersion(4, 3, api3Service, newGeneration, api3Service, retry),
        )
    }

    @Test
    fun requiredApiOneSkipsTheProbeButStillRejectsAReplacedSession() {
        val retry = BinderRetryState(cooldownMs = 0L) { 0L }
        val oldGeneration = retry.beginAttempt()!!
        assertTrue(retry.markBound(oldGeneration))
        val oldService = FakeQuery()

        assertEquals(
            ApiDecision.ALLOWED,
            decideApiVersion(1, 1, oldService, oldGeneration, oldService, retry),
        )

        assertTrue(retry.markFailed(oldGeneration))
        val newGeneration = retry.beginAttempt()!!
        assertTrue(retry.markBound(newGeneration))
        val newService = FakeQuery()

        assertEquals(
            ApiDecision.STALE_SESSION,
            decideApiVersion(1, 1, oldService, oldGeneration, newService, retry),
        )
        assertEquals(
            ApiDecision.ALLOWED,
            decideApiVersion(1, 1, newService, newGeneration, newService, retry),
        )
    }

    private class FakeQuery : IPhocidQuery.Default()
}
