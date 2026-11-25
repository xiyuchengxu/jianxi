package dev.rain.laundry.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PointsWorkflowTest {
    private class Fake : PointsGateway {
        val calls = mutableListOf<String>()
        val signedAnswers = ArrayDeque<Boolean>()
        var activity = "current-server-activity"
        var mutationFailure: Exception? = null
        var statusFailure: Exception? = null
        var afterCall: (String) -> Unit = {}
        var pauseStatus: CompletableDeferred<Unit>? = null

        private fun record(value: String) { calls += value; afterCall(value) }
        override suspend fun balance(): String { record("balance"); return "12.5" }
        override suspend fun tasks(): List<PointsTask> {
            record("tasks")
            return listOf(PointsTask("browse", "Browse", completed = 2, limit = 3))
        }
        override suspend fun signedInToday(): Boolean {
            record("status")
            pauseStatus?.await()
            statusFailure?.let { throw it }
            return if (signedAnswers.isEmpty()) false else signedAnswers.removeFirst()
        }
        override suspend fun signInActivity(): String { record("activity"); return activity }
        override suspend fun signIn(activityId: String) {
            record("sign:$activityId")
            mutationFailure?.let { throw it }
        }
    }

    @Test fun signInUsesCurrentActivityAndRequiresFreshConfirmation() = runTest {
        val fake = Fake().apply { signedAnswers.addAll(listOf(false, true)) }
        assertTrue(PointsWorkflow(fake).autoSignIn())
        assertEquals(listOf("status", "activity", "sign:current-server-activity", "status"), fake.calls)
    }

    @Test fun alreadySignedDoesNotLoadActivityOrSendMutation() = runTest {
        val fake = Fake().apply { signedAnswers += true }
        assertFalse(PointsWorkflow(fake).autoSignIn())
        assertEquals(listOf("status"), fake.calls)
    }

    @Test fun successAcknowledgementWithoutConfirmedStatusIsUnknown() = runTest {
        val fake = Fake().apply { signedAnswers.addAll(listOf(false, false)) }
        assertTrue(runCatching { PointsWorkflow(fake).autoSignIn() }.exceptionOrNull() is PointsResultUnknown)
        assertEquals(1, fake.calls.count { it.startsWith("sign:") })
        assertEquals(2, fake.calls.count { it == "status" })
    }

    @Test fun rejectedOrFalseMutationIsNotReplayedOrCountedAsSuccess() = runTest {
        val fake = Fake().apply { mutationFailure = PointsResultUnknown() }
        assertTrue(runCatching { PointsWorkflow(fake).autoSignIn() }.exceptionOrNull() is PointsResultUnknown)
        assertEquals(listOf("status", "activity", "sign:current-server-activity", "status"), fake.calls)
    }

    @Test fun networkFailureIsReconciledByOneReadWithoutReplayingMutation() = runTest {
        val fake = Fake().apply {
            signedAnswers.addAll(listOf(false, false))
            mutationFailure = ClientError("Connection ended before acknowledgement")
        }
        assertTrue(runCatching { PointsWorkflow(fake).autoSignIn() }.exceptionOrNull() is PointsResultUnknown)
        assertEquals(1, fake.calls.count { it.startsWith("sign:") })
        assertEquals(2, fake.calls.count { it == "status" })
    }

    @Test fun unknownMutationMaySucceedOnlyWhenCurrentStatusConfirmsIt() = runTest {
        val fake = Fake().apply {
            signedAnswers.addAll(listOf(false, true))
            mutationFailure = ClientError("Acknowledgement lost")
        }
        assertTrue(PointsWorkflow(fake).autoSignIn())
        assertEquals(1, fake.calls.count { it.startsWith("sign:") })
    }

    @Test fun failedConfirmationReadLeavesAnUnknownResult() = runTest {
        val fake = Fake().apply {
            afterCall = { if (it.startsWith("sign:")) statusFailure = ClientError("Offline") }
        }
        assertTrue(runCatching { PointsWorkflow(fake).autoSignIn() }.exceptionOrNull() is PointsResultUnknown)
        assertEquals(1, fake.calls.count { it.startsWith("sign:") })
        assertEquals(2, fake.calls.count { it == "status" })
    }

    @Test fun expiredSessionFromMutationPropagatesWithoutFurtherReads() = runTest {
        val fake = Fake().apply { mutationFailure = SessionExpired() }
        assertTrue(runCatching { PointsWorkflow(fake).autoSignIn() }.exceptionOrNull() is SessionExpired)
        assertEquals(listOf("status", "activity", "sign:current-server-activity"), fake.calls)
    }

    @Test fun expiredSessionFromConfirmationIsNotHiddenAsUnknown() = runTest {
        val fake = Fake().apply {
            afterCall = { if (it.startsWith("sign:")) statusFailure = SessionExpired() }
        }
        assertTrue(runCatching { PointsWorkflow(fake).autoSignIn() }.exceptionOrNull() is SessionExpired)
        assertEquals(1, fake.calls.count { it.startsWith("sign:") })
    }

    @Test fun staleSessionDoesNotSendAnyRequests() = runTest {
        val fake = Fake()
        assertTrue(runCatching { PointsWorkflow(fake) { false }.autoSignIn() }.exceptionOrNull() is CancellationException)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun accountChangeAfterActivityReadPreventsMutation() = runTest {
        var current = true
        val fake = Fake().apply { afterCall = { if (it == "activity") current = false } }
        assertTrue(runCatching { PointsWorkflow(fake) { current }.autoSignIn() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("status", "activity"), fake.calls)
    }

    @Test fun accountChangeAfterMutationPreventsConfirmationOrReplay() = runTest {
        var current = true
        val fake = Fake().apply { afterCall = { if (it.startsWith("sign:")) current = false } }
        assertTrue(runCatching { PointsWorkflow(fake) { current }.autoSignIn() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("status", "activity", "sign:current-server-activity"), fake.calls)
    }

    @Test fun cancellingAnInFlightReadPreventsAllFollowingRequests() = runTest {
        val fake = Fake().apply { pauseStatus = CompletableDeferred() }
        val job = launch { PointsWorkflow(fake).autoSignIn() }
        runCurrent()
        assertEquals(listOf("status"), fake.calls)
        job.cancel()
        fake.pauseStatus!!.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("status"), fake.calls)
    }

    @Test fun refreshOnlyReadsAndPreservesServerCounters() = runTest {
        val fake = Fake().apply { signedAnswers += true }
        val result = PointsWorkflow(fake).refresh()
        assertEquals(listOf("balance", "status", "tasks"), fake.calls)
        assertEquals("12.5", result.balance)
        assertTrue(result.signedInToday)
        assertEquals(2, result.tasks.single().completed)
        assertEquals(3, result.tasks.single().limit)
    }

    @Test fun accountChangeDuringRefreshDoesNotContinueWithAnotherAccount() = runTest {
        var current = true
        val fake = Fake().apply { afterCall = { if (it == "balance") current = false } }
        assertTrue(runCatching { PointsWorkflow(fake) { current }.refresh() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("balance"), fake.calls)
    }
}
