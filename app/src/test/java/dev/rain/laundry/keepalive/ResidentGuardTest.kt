package dev.rain.laundry.keepalive

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResidentGuardTest {
    private class Lease : ResidentLease {
        var alive = true
        var closes = 0
        override fun isAlive() = alive
        override fun close() { closes++ }
    }
    private class Target : ResidentTarget {
        var allowed = true
        var holds = 0
        val lease = Lease()
        var duringAcquire: () -> Unit = {}
        override fun mayHold() = allowed
        override fun acquire(): ResidentLease { holds++; duringAcquire(); return lease }
    }
    @Test fun defaultNeverAcquiresUntilExplicitStart() {
        val target = Target(); val guard = ResidentGuard(target)
        assertFalse(guard.refresh()); assertFalse(guard.isRunning()); assertEquals(0, target.holds)
    }
    @Test fun repeatedRefreshRetainsOnlyOneReferenceAndStopReleasesOnce() {
        val target = Target(); val guard = ResidentGuard(target)
        assertTrue(guard.start()); repeat(4) { assertTrue(guard.refresh()) }
        assertEquals(1, target.holds)
        guard.stop(); guard.stop()
        assertFalse(guard.isRunning()); assertEquals(1, target.lease.closes)
    }
    @Test fun forceStoppedInstallationIsNeverAcquired() {
        val target = Target().apply { allowed = false }; val guard = ResidentGuard(target)
        assertFalse(guard.start()); assertFalse(guard.isRequested()); assertEquals(0, target.holds)
    }
    @Test fun stoppedOrReplacedInstallationReleasesItsExistingHandle() {
        val target = Target(); val guard = ResidentGuard(target)
        assertTrue(guard.start()); target.allowed = false
        assertFalse(guard.refresh()); assertFalse(guard.isRequested()); assertEquals(1, target.lease.closes)
    }
    @Test fun deadProviderDoesNotLaunchTheAppAgain() {
        val target = Target(); val guard = ResidentGuard(target)
        assertTrue(guard.start()); target.lease.alive = false
        assertFalse(guard.refresh()); assertFalse(guard.isRequested()); assertEquals(1, target.holds)
        assertEquals(1, target.lease.closes)
    }
    @Test fun stopWhileAcquireIsBlockedDiscardsTheLateReference() {
        val target = Target(); val guard = ResidentGuard(target)
        val entered = CountDownLatch(1); val continueAcquire = CountDownLatch(1)
        target.duringAcquire = { entered.countDown(); check(continueAcquire.await(2, TimeUnit.SECONDS)) }
        val starting = Thread { guard.start() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopping = Thread { guard.stop() }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (guard.isRequested() && System.nanoTime() < deadline) Thread.yield()
        assertFalse(guard.isRequested())
        continueAcquire.countDown(); starting.join(2000); stopping.join(2000)
        assertFalse(starting.isAlive); assertFalse(stopping.isAlive)
        assertFalse(guard.isRunning()); assertEquals(1, target.lease.closes)
    }
    @Test fun validationFailureAfterAcquireStillReleasesTheReference() {
        val target = Target(); val guard = ResidentGuard(target)
        target.duringAcquire = { target.allowed = false }
        assertFalse(guard.start()); assertFalse(guard.isRunning()); assertEquals(1, target.lease.closes)
    }
}
