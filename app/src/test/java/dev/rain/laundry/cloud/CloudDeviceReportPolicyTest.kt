package dev.rain.laundry.cloud

import com.google.gson.Gson
import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.OrderPhase
import org.junit.Assert.*
import org.junit.Test

class CloudDeviceReportPolicyTest {
    private val now = 1_900_000_000_000L

    @Test fun startsOnlyFromACurrentPaidRunningSnapshot() {
        assertNull(CloudDeviceReportPolicy.observe(null, observation(
            phase = OrderPhase.COMPLETED, run = LaundryRunState.FINISHED,
            completion = true)))
        assertNull(CloudDeviceReportPolicy.observe(null, observation(run = null)))
        assertNull(CloudDeviceReportPolicy.observe(null, observation(isWater = true)))
        assertNull(CloudDeviceReportPolicy.observe(null, observation(ownerMatches = false)))
        assertNull(CloudDeviceReportPolicy.observe(null, observation(phase = OrderPhase.CREATED)))

        val record = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        assertEquals("order-key", record.orderKey)
        assertFalse(record.runningAcked)
        assertEquals(now + 60 * 60 * 1000L, record.runningExpectedEndAtMs)
        assertEquals("running", CloudDeviceReportPolicy.nextReport(record)?.state)
        assertNull(CloudDeviceReportPolicy.observe(record,
            observation().copy(orderKey = "other-order")))
    }

    @Test fun unchangedRunningStateAndEstimateDoesNotReportEveryPoll() {
        val tracked = acknowledgedRunning(requireNotNull(
            CloudDeviceReportPolicy.observe(null, observation())))
        val later = CloudDeviceReportPolicy.observe(tracked, observation(observedAt = now + 15_000L))!!
        assertTrue(later.runningAcked)
        assertEquals(tracked.runningObservedAtMs, later.runningObservedAtMs)
        assertNull(CloudDeviceReportPolicy.nextReport(later))

        val changed = CloudDeviceReportPolicy.observe(tracked,
            observation(observedAt = now + 30_000L,
                expected = now + 2 * 60 * 60 * 1000L))!!
        assertFalse(changed.runningAcked)
        val report = requireNotNull(CloudDeviceReportPolicy.nextReport(changed))
        assertEquals("running", report.state)
        assertEquals(now + 30_000L, report.observedAtMs)
        assertEquals(now + 2 * 60 * 60 * 1000L, report.expectedEndAtMs)
    }

    @Test fun unknownOrInvalidEstimateNeverBecomesAnInventedDeadline() {
        val noEstimate = requireNotNull(
            CloudDeviceReportPolicy.observe(null, observation(expected = null)))
        assertNull(CloudDeviceReportPolicy.nextReport(noEstimate)?.expectedEndAtMs)

        val expired = requireNotNull(
            CloudDeviceReportPolicy.observe(null, observation(expected = now - 1)))
        assertNull(CloudDeviceReportPolicy.nextReport(expired)?.expectedEndAtMs)

        val tooLong = requireNotNull(CloudDeviceReportPolicy.observe(null,
            observation(expected = now + 13 * 60 * 60 * 1000L)))
        assertNull(CloudDeviceReportPolicy.nextReport(tooLong)?.expectedEndAtMs)
    }

    @Test fun offlineCompletionStillSendsPersistedRunningFirst() {
        val running = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val completed = requireNotNull(CloudDeviceReportPolicy.observe(running,
            observation(phase = OrderPhase.COMPLETED, run = LaundryRunState.FINISHED,
                completion = true, observedAt = now + 60_000L)))
        val first = requireNotNull(CloudDeviceReportPolicy.nextReport(completed))
        assertEquals("running", first.state)
        assertEquals(now, first.observedAtMs)

        val runningAcked = CloudDeviceReportPolicy.acknowledged(completed, first,
            subscription("active"))
        val second = requireNotNull(CloudDeviceReportPolicy.nextReport(runningAcked))
        assertEquals("completed", second.state)
        assertEquals(now + 60_000L, second.observedAtMs)
        assertNull(second.expectedEndAtMs)

        val terminal = CloudDeviceReportPolicy.acknowledged(runningAcked, second,
            subscription("completed"))
        assertTrue(terminal.completionAcked)
        assertNull(CloudDeviceReportPolicy.nextReport(terminal))
    }

    @Test fun completedPhaseWithoutExplicitConfirmationIsNotACompletion() {
        val running = acknowledgedRunning(requireNotNull(
            CloudDeviceReportPolicy.observe(null, observation())))
        val completedPhase = CloudDeviceReportPolicy.observe(running,
            observation(phase = OrderPhase.COMPLETED, run = LaundryRunState.FINISHED,
                completion = false, observedAt = now + 60_000L))!!
        assertNull(completedPhase.completionObservedAtMs)
        assertNull(CloudDeviceReportPolicy.nextReport(completedPhase))
    }

    @Test fun lateRunningCannotResurrectACompletedRecord() {
        val running = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val completedObserved = requireNotNull(CloudDeviceReportPolicy.observe(running,
            observation(phase = OrderPhase.COMPLETED, run = LaundryRunState.FINISHED,
                completion = true, observedAt = now + 60_000L)))
        val runningReport = requireNotNull(CloudDeviceReportPolicy.nextReport(completedObserved))
        val runningAcked = CloudDeviceReportPolicy.acknowledged(completedObserved, runningReport,
            subscription("active"))
        val completedReport = requireNotNull(CloudDeviceReportPolicy.nextReport(runningAcked))
        val completed = CloudDeviceReportPolicy.acknowledged(runningAcked, completedReport,
            subscription("completed"))
        val lateRunning = CloudDeviceReportPolicy.observe(completed,
            observation(observedAt = now + 120_000L))!!
        assertEquals(completed, lateRunning)
        assertNull(CloudDeviceReportPolicy.nextReport(lateRunning))
    }

    @Test fun cancellationRevokesUnsentEventsAndCannotBeResurrectedByLateRunning() {
        val running = acknowledgedRunning(requireNotNull(
            CloudDeviceReportPolicy.observe(null, observation())))
        val cancelled = CloudDeviceReportPolicy.observe(running,
            observation(phase = OrderPhase.REFUNDING, run = LaundryRunState.UNKNOWN,
                observedAt = now + 60_000L))!!
        val report = requireNotNull(CloudDeviceReportPolicy.nextReport(cancelled))
        assertEquals("cancelled", report.state)
        assertEquals(now + 60_000L, report.observedAtMs)
        val acked = CloudDeviceReportPolicy.acknowledged(cancelled, report,
            subscription("cancelled"))
        assertTrue(acked.cancelledAcked)
        val lateRunning = CloudDeviceReportPolicy.observe(acked,
            observation(observedAt = now + 120_000L))!!
        assertNull(CloudDeviceReportPolicy.nextReport(lateRunning))
    }

    @Test fun terminalAndUnsupportedFailuresBlockUntilForcedRetry() {
        val record = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val blocked = CloudDeviceReportPolicy.terminalFailure(record, "http_409")
        assertTrue(blocked.blocked)
        assertNull(CloudDeviceReportPolicy.nextReport(blocked))
        assertEquals("running", CloudDeviceReportPolicy.nextReport(
            CloudDeviceReportPolicy.prepareForced(blocked))?.state)

        val unsupported = CloudDeviceReportPolicy.unsupportedFailure(record)
        assertTrue(unsupported.unsupported)
        assertNull(CloudDeviceReportPolicy.nextReport(unsupported))
        assertNotNull(CloudDeviceReportPolicy.nextReport(
            CloudDeviceReportPolicy.prepareForced(unsupported), force = true))
    }

    @Test fun acknowledgementStoresOnlyServerReadbackAndNeverClaimsDelivery() {
        val record = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val report = requireNotNull(CloudDeviceReportPolicy.nextReport(record))
        val acked = CloudDeviceReportPolicy.acknowledged(record, report,
            subscription("active", source = "device_report",
                clientState = "running", completionSource = null,
                estimatedState = "accepted", estimatedAccepted = true,
                estimatedDelivered = false))
        assertEquals("device_report", acked.serverSource)
        assertEquals("running", acked.serverClientState)
        assertEquals("accepted", acked.estimatedDeliveryState)
        assertTrue(acked.estimatedAccepted)
        assertFalse(acked.estimatedDelivered)
        assertNull(acked.lastIssue)
    }

    @Test fun identicalSnapshotKeepsBlockedAndUnsupportedSeals() {
        val original = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val blocked = CloudDeviceReportPolicy.terminalFailure(original, "http_409")
        val same = requireNotNull(CloudDeviceReportPolicy.observe(blocked,
            observation(observedAt = now + 15_000L)))
        assertTrue(same.blocked)
        assertEquals(blocked.runningObservedAtMs, same.runningObservedAtMs)
        assertNull(CloudDeviceReportPolicy.nextReport(same))

        val changed = requireNotNull(CloudDeviceReportPolicy.observe(blocked,
            observation(observedAt = now + 30_000L,
                expected = now + 2 * 60 * 60 * 1000L)))
        assertFalse(changed.blocked)
        assertEquals(now + 30_000L, changed.runningObservedAtMs)
        assertEquals(now + 2 * 60 * 60 * 1000L,
            CloudDeviceReportPolicy.nextReport(changed)?.expectedEndAtMs)

        val unsupported = CloudDeviceReportPolicy.unsupportedFailure(original)
        val sameUnsupported = requireNotNull(CloudDeviceReportPolicy.observe(unsupported,
            observation(observedAt = now + 45_000L)))
        assertTrue(sameUnsupported.unsupported)
        assertNull(CloudDeviceReportPolicy.nextReport(sameUnsupported))
    }

    @Test fun olderObservationCannotReplaceANewerEstimate() {
        val original = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val newer = requireNotNull(CloudDeviceReportPolicy.observe(original,
            observation(observedAt = now + 60_000L,
                expected = now + 2 * 60 * 60 * 1000L)))
        val older = requireNotNull(CloudDeviceReportPolicy.observe(newer,
            observation(observedAt = now + 30_000L,
                expected = now + 3 * 60 * 60 * 1000L)))
        assertEquals(now + 60_000L, older.runningObservedAtMs)
        assertEquals(now + 2 * 60 * 60 * 1000L, older.runningExpectedEndAtMs)
    }

    @Test fun originalExpectedRemainsAfterItsDeadlineButSamePollDoesNotRefreshTimestamp() {
        val expected = now + 60 * 60 * 1000L
        val running = requireNotNull(CloudDeviceReportPolicy.observe(null,
            observation(expected = expected)))
        val afterDeadline = requireNotNull(CloudDeviceReportPolicy.observe(running,
            observation(observedAt = expected + 60 * 60 * 1000L, expected = expected)))
        assertEquals(expected, afterDeadline.runningExpectedEndAtMs)
        assertEquals(now, afterDeadline.runningObservedAtMs)
        assertEquals("running", CloudDeviceReportPolicy.nextReport(afterDeadline)?.state)

        val acked = acknowledgedRunning(running)
        val stable = requireNotNull(CloudDeviceReportPolicy.observe(acked,
            observation(observedAt = expected + 2 * 60 * 60 * 1000L, expected = expected)))
        assertEquals(expected, stable.runningExpectedEndAtMs)
        assertNull(CloudDeviceReportPolicy.nextReport(stable))
    }

    @Test fun terminalObservationIsStickyBeforeAck() {
        val running = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val completed = requireNotNull(CloudDeviceReportPolicy.observe(running,
            observation(phase = OrderPhase.COMPLETED, run = LaundryRunState.FINISHED,
                completion = true, observedAt = now + 60_000L)))
        assertFalse(completed.completionAcked)
        assertNotNull(completed.completionObservedAtMs)
        val lateRunning = requireNotNull(CloudDeviceReportPolicy.observe(completed,
            observation(observedAt = now + 120_000L)))
        assertEquals(completed, lateRunning)
    }

    @Test fun staleAckPayloadDoesNotAdvanceTheRecord() {
        val original = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        val staleReport = requireNotNull(CloudDeviceReportPolicy.nextReport(original))
        val newer = requireNotNull(CloudDeviceReportPolicy.observe(original,
            observation(observedAt = now + 60_000L,
                expected = now + 2 * 60 * 60 * 1000L)))
        assertFalse(CloudDeviceReportPolicy.isCurrentReport(newer, staleReport))
        assertEquals(newer, CloudDeviceReportPolicy.acknowledged(newer, staleReport,
            subscription("active")))
        assertTrue(CloudDeviceReportPolicy.isCurrentReport(newer,
            requireNotNull(CloudDeviceReportPolicy.nextReport(newer))))
    }

    @Test fun strongIdentityAndStatusReadbackMatchingPreferOrderId() {
        val report = CloudOrderReport("order-id", "number-1", "running", now, null)
        assertTrue(CloudDeviceReportPolicy.sameStrongIdentity(report,
            report.copy(orderNo = "other-number")))
        assertFalse(CloudDeviceReportPolicy.sameStrongIdentity(report,
            report.copy(orderId = "other-id")))
        val record = requireNotNull(CloudDeviceReportPolicy.observe(null, observation()))
        assertTrue(CloudDeviceReportPolicy.matchesSubscription(record,
            subscription("active").copy(orderId = "order-id", orderNo = "other-number")))
        assertFalse(CloudDeviceReportPolicy.matchesSubscription(record,
            subscription("active").copy(orderId = "other-id", orderNo = "order-no")))
    }

    @Test fun legacyStoredStateWithoutDeviceReportsNormalizesToEmpty() {
        val legacy = Gson().fromJson("""{"owner":"owner-a"}""", CloudStoredState::class.java)
        assertNull(runCatching { legacy.deviceReports }.getOrNull())
        assertTrue(legacy.normalizedForLoad().deviceReports.isEmpty())
    }

    @Test fun olderTerminalObservationCannotPoisonARealLaterCompletion() {
        val running = acknowledgedRunning(requireNotNull(CloudDeviceReportPolicy.observe(null, observation())))
        for (old in listOf(
            observation(phase = OrderPhase.COMPLETED, completion = true, observedAt = now - 1),
            observation(phase = OrderPhase.CANCELLED, observedAt = now - 1)
        )) assertEquals(running, CloudDeviceReportPolicy.observe(running, old))
        val done = requireNotNull(CloudDeviceReportPolicy.observe(running,
            observation(phase = OrderPhase.COMPLETED, completion = true, observedAt = now + 1)))
        assertEquals("completed", CloudDeviceReportPolicy.nextReport(done)?.state)
        assertEquals(now + 1, done.completionObservedAtMs)
    }

    private fun acknowledgedRunning(record: CloudDeviceReportRecord): CloudDeviceReportRecord {
        val report = requireNotNull(CloudDeviceReportPolicy.nextReport(record))
        return CloudDeviceReportPolicy.acknowledged(record, report, subscription("active"))
    }

    private fun observation(phase: OrderPhase = OrderPhase.PAID,
        run: LaundryRunState? = LaundryRunState.RUNNING,
        completion: Boolean = false,
        expected: Long? = now + 60 * 60 * 1000L,
        observedAt: Long = now,
        ownerMatches: Boolean = true,
        isWater: Boolean = false
    ) = CloudDeviceObservation(
        ownerMatches = ownerMatches,
        isWater = isWater,
        phase = phase,
        runState = run,
        completionConfirmed = completion,
        expectedEndAtMs = expected,
        observedAtMs = observedAt,
        orderKey = "order-key",
        orderId = "order-id",
        orderNo = "order-no",
        nowMs = now
    )

    private fun subscription(state: String, source: String? = "device_report",
        clientState: String? = null, completionSource: String? = null,
        estimatedState: String? = null, estimatedAccepted: Boolean = false,
        estimatedDelivered: Boolean = false
    ) = CloudSubscriptionSummary(
        id = "sub-1", orderId = "order-id", orderNo = "order-no",
        state = state, deliveryState = "none", accepted = false, delivered = false,
        source = source, expectedEndAtMs = now + 60 * 60 * 1000L,
        clientState = clientState, completionSource = completionSource,
        estimatedDeliveryState = estimatedState, estimatedAccepted = estimatedAccepted,
        estimatedDelivered = estimatedDelivered
    )
}
