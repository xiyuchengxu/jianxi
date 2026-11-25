package dev.rain.laundry.cloud

import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.OrderPhase
import org.junit.Assert.*
import org.junit.Test

class CloudDeviceReportQueueTest {
    private val now = 1_794_655_200_000L
    private fun running(key: String, observed: Long = now) = CloudDeviceObservation(
        true, false, true, OrderPhase.PAID, LaundryRunState.RUNNING, false,
        now + 60_000, observed, key, "id-$key", "", now)
    private fun tracked(key: String) = requireNotNull(CloudDeviceReportPolicy.observe(null, running(key)))

    @Test fun changingCurrentOrderRetainsEarlierCompletionAndItsRunningPrerequisite() {
        val first = tracked("a")
        val done = running("a", now + 10_000).copy(phase = OrderPhase.COMPLETED,
            runState = LaundryRunState.FINISHED, completionConfirmed = true)
        val result = CloudDeviceReportQueue.observe(mapOf("a" to first), running("b"), listOf(done))
        assertEquals(setOf("a", "b"), result.keys)
        assertEquals(now + 10_000, result.getValue("a").completionObservedAtMs)
        val next = requireNotNull(CloudDeviceReportQueue.next(result))
        assertEquals("a", next.first)
        assertEquals("running", next.second.state)
    }

    @Test fun pendingRunningSurvivesMissingWorkflowPointer() {
        val original = mapOf("a" to tracked("a"))
        val result = CloudDeviceReportQueue.observe(original, null, emptyList())
        assertEquals(original, result)
        assertEquals("a", CloudDeviceReportQueue.next(result)?.first)
    }

    @Test fun waterPointerDoesNotReplacePendingLaundryEvidence() {
        val original = mapOf("a" to tracked("a"))
        val result = CloudDeviceReportQueue.observe(original, running("water").copy(isWater = true, isLaundry = false), emptyList())
        assertEquals(original, result)
        assertEquals("a", CloudDeviceReportQueue.next(result)?.first)
    }

    @Test fun untrackedHistoryCannotStartReports() {
        val result = CloudDeviceReportQueue.observe(emptyMap(), null, listOf(running("history")))
        assertTrue(result.isEmpty())
    }

    @Test fun currentAlreadyCompletedWithoutEvidenceCannotBackfill() {
        val done = running("old").copy(phase = OrderPhase.COMPLETED,
            runState = LaundryRunState.FINISHED, completionConfirmed = true)
        assertTrue(CloudDeviceReportQueue.observe(emptyMap(), done, listOf(done)).isEmpty())
    }

    @Test fun automaticObservationKeepsStopWhileExplicitRetryUnblocks() {
        val failed = tracked("a").copy(blocked = true, unsupported = true)
        val automatic = CloudDeviceReportQueue.observe(mapOf("a" to failed), running("a"), emptyList())
        assertNull(CloudDeviceReportQueue.next(automatic))
        val manual = CloudDeviceReportQueue.observe(automatic, running("a"), emptyList(), force = true)
        assertNotNull(CloudDeviceReportQueue.next(manual))
    }

    @Test fun settledHistoryMakesRoomWithoutEvictingUnsentTransitions() {
        val previous = (1..128).associate { "$it" to tracked("$it").copy(completionAcked = true) }
        val result = CloudDeviceReportQueue.observe(previous, running("new"), emptyList())
        assertEquals(128, result.size)
        assertTrue("new" in result)
    }

    @Test fun fullUnsentQueueKeepsEvidenceInsteadOfSilentlyDroppingOldOrders() {
        val previous = (1..128).associate { "$it" to tracked("$it") }
        val result = CloudDeviceReportQueue.observe(previous, running("new"), emptyList())
        assertEquals(previous, result)
    }

    @Test fun currentOrderDisplayDoesNotKeepAnEarlierCompletedOrder() {
        val first = tracked("a").copy(completionObservedAtMs = now + 5_000,
            completionAcked = true, runningAcked = true)
        val second = tracked("b").copy(blocked = true, lastIssue = "http_422")
        val records = listOf(first, second)
        assertEquals(second, CloudDeviceReportQueue.display(records, "b"))
        assertEquals(second, CloudDeviceReportQueue.display(records, null))
        assertEquals(CloudIssueKind.DEVICE_REPORT_REJECTED, CloudDeviceReportQueue.issue(records)?.kind)
    }

    @Test fun nonCurrentFailedOrderStillOffersRetry() {
        val failed = tracked("a").copy(blocked = true, lastIssue = "http_409")
        val current = tracked("b").copy(runningAcked = true)
        assertEquals(current, CloudDeviceReportQueue.display(listOf(failed, current), "b"))
        assertEquals(CloudIssueKind.DEVICE_REPORT_REJECTED,
            CloudDeviceReportQueue.issue(listOf(current, failed))?.kind)
    }
}
