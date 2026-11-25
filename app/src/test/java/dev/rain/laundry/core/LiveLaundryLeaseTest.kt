package dev.rain.laundry.core

import org.junit.Assert.*
import org.junit.Test

class LiveLaundryLeaseTest {
    private fun gate(): LiveLaundryLeaseGate {
        var id = 0
        return LiveLaundryLeaseGate { "opaque-${++id}" }
    }

    @Test fun lateStartAndFinishCannotReplaceTheNewAccount() {
        val gate = gate()
        val old = requireNotNull(gate.request("owner-a", "token-a", "wash-a"))
        assertTrue(gate.activate(old))
        val next = requireNotNull(gate.request("owner-b", "token-b", "wash-b"))
        assertNull(gate.forStart(old.id))
        assertFalse(gate.activate(old))
        assertFalse(gate.invalidate(old.id))
        assertEquals(next, gate.forStart(next.id))
        assertTrue(gate.activate(next))
        assertTrue(gate.isActive(next))
    }

    @Test fun aNewTokenForTheSameOwnerAndOrderInvalidatesTheOldSession() {
        val gate = gate()
        val old = requireNotNull(gate.request("owner-a", "token-1", "wash-a"))
        val next = requireNotNull(gate.request("owner-a", "token-2", "wash-a"))
        assertNotEquals(old.id, next.id)
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.matchesDismiss(old.owner, old.orderKey, old.id))
        assertTrue(gate.matchesDismiss(next.owner, next.orderKey, next.id))
    }

    @Test fun duplicatePendingAndActiveStartsReuseTheLease() {
        val gate = gate()
        val pending = requireNotNull(gate.request("owner-a", "token-a", "wash-a"))
        assertEquals(pending, gate.forIdentity("owner-a", "token-a", "wash-a"))
        assertEquals(pending, gate.request("owner-a", "token-a", "wash-a"))
        assertTrue(gate.activate(pending))
        assertEquals(pending, gate.request("owner-a", "token-a", "wash-a"))
        assertTrue(gate.isActive(pending))
    }

    @Test fun stoppingMakesAnAlreadyQueuedStartAndDismissInert() {
        val gate = gate()
        val pending = requireNotNull(gate.request("owner-a", "token-a", "wash-a"))
        gate.invalidate()
        assertNull(gate.forStart(pending.id))
        assertFalse(gate.activate(pending))
        assertFalse(gate.matchesDismiss(pending.owner, pending.orderKey, pending.id))
    }

    @Test fun oldDismissCannotStopAnExplicitResumeOfTheSameWash() {
        val gate = gate()
        val old = requireNotNull(gate.request("owner-a", "token-a", "wash-a"))
        assertTrue(gate.invalidate(old.id))
        val resumed = requireNotNull(gate.request("owner-a", "token-a", "wash-a"))
        assertFalse(gate.matchesDismiss(old.owner, old.orderKey, old.id))
        assertFalse(gate.invalidate(old.id))
        assertTrue(gate.isCurrent(resumed))
    }

    @Test fun dismissalNeedsAllThreePublicIdentityValues() {
        val gate = gate()
        val lease = requireNotNull(gate.request("owner-a", "token-a", "wash-a"))
        assertFalse(gate.matchesDismiss("owner-b", lease.orderKey, lease.id))
        assertFalse(gate.matchesDismiss(lease.owner, "wash-b", lease.id))
        assertFalse(gate.matchesDismiss(lease.owner, lease.orderKey, "older-id"))
        assertTrue(gate.matchesDismiss(lease.owner, lease.orderKey, lease.id))
    }

    @Test fun invalidRequestsDoNotInvalidateAnExistingValidMonitor() {
        val gate = gate()
        val lease = requireNotNull(gate.request("owner-a", "token-a", "wash-a"))
        assertNull(gate.request("", "token-b", "wash-b"))
        assertNull(gate.request("owner-b", "", "wash-b"))
        assertNull(gate.request("owner-b", "token-b", ""))
        assertTrue(gate.isCurrent(lease))
    }

    @Test fun diagnosticRepresentationDoesNotExposeTheCredential() {
        val lease = requireNotNull(gate().request("owner-a", "private-token", "wash-a"))
        assertFalse(lease.toString().contains("private-token"))
    }
}
