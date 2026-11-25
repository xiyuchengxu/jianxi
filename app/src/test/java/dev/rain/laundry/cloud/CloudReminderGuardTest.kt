package dev.rain.laundry.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CloudReminderGuardTest {
    @Test fun bindingCasRequiresAllCredentialsButAllowsConfigurationUpdates() {
        val binding = CloudBinding("owner-a", "https://reminders.example", "device-token-a",
            "account-a", "device-a", "Phone")
        assertFalse(binding.copy(deviceToken = "device-token-b").sameCredentialsAs(binding))
        assertFalse(binding.copy(serverUrl = "https://other.example").sameCredentialsAs(binding))
        assertFalse(binding.copy(accountId = "account-b").sameCredentialsAs(binding))
        assertFalse(binding.copy(deviceId = "device-b").sameCredentialsAs(binding))
        assertFalse(binding.copy(owner = "owner-b").sameCredentialsAs(binding))
        assertFalse(binding.copy(deviceAssisted = true).sameCredentialsAs(binding))
        assertTrue(binding.copy(notificationConfigured = true, accountStatus = "auth_required",
            deviceLabel = "Renamed phone", consentToReadOnlyQuery = false)
            .sameCredentialsAs(binding))
        val missing: CloudBinding? = null
        assertTrue(missing.sameCredentialsAs(null))
        assertFalse(missing.sameCredentialsAs(binding))
        assertFalse(binding.sameCredentialsAs(null))
    }

    @Test fun logoutResultBelongsOnlyToTheLogoutThatCreatedIt() {
        val guard = CloudReminderGuard()
        guard.reset("owner-a")
        guard.reset("")
        val firstLogout = guard.snapshot()
        assertEquals("", firstLogout.owner)
        assertTrue(guard.withCurrent(firstLogout) { true } == true)
        assertNull(guard.session(""))

        guard.reset("owner-a")
        guard.reset("")

        assertNull(guard.withCurrent(firstLogout) { "stale logout notice" })
        assertTrue(guard.withCurrent(guard.snapshot()) { true } == true)
    }

    @Test fun sameAccountLoginDoesNotAcceptAnEarlierSessionsResponse() {
        val guard = CloudReminderGuard()
        guard.reset("owner-a")
        val oldSession = requireNotNull(guard.session("owner-a"))
        val oldOperation = requireNotNull(guard.begin("owner-a"))

        guard.reset("")
        guard.reset("owner-a")

        var writes = 0
        assertNull(guard.withCurrent(oldSession) { writes++ })
        assertNull(guard.withCurrent(oldOperation) { writes++ })
        val current = requireNotNull(guard.session("owner-a"))
        assertTrue(current.epoch > oldSession.epoch)
        assertEquals(0, writes)
        assertTrue(guard.withCurrent(current) { true } == true)
    }

    @Test fun delayedFailureCannotReplaceTheNewerOperationsSuccess() {
        val guard = CloudReminderGuard()
        guard.reset("owner-a")
        val old = requireNotNull(guard.begin("owner-a"))
        val releaseFailure = CountDownLatch(1)
        val failureReady = CountDownLatch(1)
        val failureAccepted = AtomicReference<Boolean>()
        val threadFailure = AtomicReference<Throwable>()
        var ui = "loading"
        val oldRequest = thread(name = "old-cloud-failure") {
            try {
                failureReady.countDown()
                await(releaseFailure)
                failureAccepted.set(guard.withCurrent(old) { ui = "error"; true } == true)
            } catch (failure: Throwable) {
                threadFailure.set(failure)
            }
        }
        try {
            await(failureReady)
            val newer = requireNotNull(guard.begin("owner-a"))
            assertTrue(guard.withCurrent(newer) { ui = "bound"; true } == true)
        } finally {
            releaseFailure.countDown()
            join(oldRequest)
        }
        threadFailure.get()?.let { throw AssertionError("Background test failed", it) }
        assertEquals(false, failureAccepted.get())
        assertEquals("bound", ui)
    }

    @Test fun resetCannotInterleaveBetweenResultValidationAndItsCommit() {
        val guard = CloudReminderGuard()
        guard.reset("owner-a")
        val operation = requireNotNull(guard.begin("owner-a"))
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val resetRequested = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val threadFailure = AtomicReference<Throwable>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        var binding: String? = null
        val callback = thread(name = "cloud-commit") {
            try {
                guard.withCurrent(operation) {
                    commitEntered.countDown()
                    await(releaseCommit)
                    binding = "old-device"
                    events += "commit"
                }
            } catch (failure: Throwable) {
                threadFailure.set(failure)
            }
        }
        val logout = thread(name = "cloud-logout") {
            try {
                await(commitEntered)
                resetRequested.countDown()
                guard.reset("") {
                    binding = null
                    events += "logout"
                }
            } catch (failure: Throwable) {
                threadFailure.set(failure)
            } finally {
                resetFinished.countDown()
            }
        }
        try {
            await(resetRequested)
            // A reset requested while the commit is held must wait for that actual commit.
            assertFalse(resetFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseCommit.countDown()
            join(callback)
            join(logout)
        }
        threadFailure.get()?.let { throw AssertionError("Background test failed", it) }
        assertEquals(listOf("commit", "logout"), events.toList())
        assertNull(binding)
        assertNull(guard.session("owner-a"))
        assertFalse(guard.isCurrent(operation))
    }

    @Test fun startingANewOperationDoesNotInvalidateBackgroundWorkForTheSession() {
        val guard = CloudReminderGuard()
        guard.reset("owner-a")
        val session = requireNotNull(guard.session("owner-a"))
        val first = requireNotNull(guard.begin("owner-a"))
        val latest = requireNotNull(guard.begin("owner-a"))

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(latest))
        assertTrue(guard.withCurrent(session) { true } == true)
        assertNull(guard.begin("owner-b"))
        assertTrue(guard.isCurrent(latest))
    }

    private fun await(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for test coordination" }
    }

    private fun join(worker: Thread) {
        worker.join(5_000)
        check(!worker.isAlive) { "Test worker did not finish: ${worker.name}" }
    }
}
