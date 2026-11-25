package dev.rain.laundry.cloud

import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.OrderPhase

/**
 * Pure device-assisted policy. It never invents an end time and never treats a local deadline or
 * a completed phase as proof of completion: completed is considered only when the caller supplies
 * the platform snapshot's explicit `completionConfirmed` flag for the same owner/order.
 */
data class CloudDeviceObservation(
    val ownerMatches: Boolean,
    val isWater: Boolean,
    val isLaundry: Boolean = !isWater,
    val phase: OrderPhase,
    val runState: LaundryRunState?,
    val completionConfirmed: Boolean,
    val expectedEndAtMs: Long?,
    val observedAtMs: Long,
    val orderKey: String,
    val orderId: String,
    val orderNo: String,
    val nowMs: Long
)

object CloudDeviceReportPolicy {
    private const val MAX_EXPECTED_AHEAD_MS = 12L * 60L * 60L * 1000L
    private val CANCEL_PHASES = setOf(
        OrderPhase.CANCELLED, OrderPhase.REJECTED, OrderPhase.CLOSED,
        OrderPhase.REFUNDING, OrderPhase.REFUNDED
    )

    /**
     * Returns the updated encrypted record, or null when this observation must not start tracking.
     * Existing tracked records may still be updated for their own order; a different order key is
     * rejected so a stale current pointer cannot fan out to history.
     */
    fun observe(tracked: CloudDeviceReportRecord?, observation: CloudDeviceObservation):
        CloudDeviceReportRecord? {
        if (!observation.ownerMatches || !observation.isLaundry) return null
        if (observation.orderKey.isBlank() || observation.observedAtMs <= 0) return null
        if (tracked != null && tracked.orderKey != observation.orderKey) return null
        if (tracked != null && observation.observedAtMs < maxOf(tracked.runningObservedAtMs,
                tracked.completionObservedAtMs ?: 0L, tracked.cancelledObservedAtMs ?: 0L))
            return tracked

        val rawExpected = observation.expectedEndAtMs
        val usable = usableExpected(rawExpected, observation.observedAtMs)
        if (tracked == null) {
            // Start only from a current, newly observed running snapshot. A completed/refunded
            // historical PAID row has no running evidence and must never create a reminder.
            if (observation.phase != OrderPhase.PAID) return null
            if (observation.runState != LaundryRunState.RUNNING) return null
            if (observation.completionConfirmed) return null
            return CloudDeviceReportRecord(
                orderKey = observation.orderKey,
                orderId = observation.orderId,
                orderNo = observation.orderNo,
                runningObservedAtMs = observation.observedAtMs,
                runningExpectedEndAtMs = usable
            )
        }

        // Preserve the original platform estimate even after the wall clock passes it. Only a
        // genuinely different, still-usable platform estimate changes the pending estimate.
        val expected = when {
            rawExpected == null -> tracked.runningExpectedEndAtMs
            rawExpected == tracked.runningExpectedEndAtMs -> tracked.runningExpectedEndAtMs
            usable != null -> usable
            else -> tracked.runningExpectedEndAtMs
        }
        val expectedChanged = expected != tracked.runningExpectedEndAtMs
        val cancelObserved = observation.phase in CANCEL_PHASES
        val completeObserved = observation.completionConfirmed

        // The same snapshot must not clear a previous failure on every poll, especially when
        // running was never acknowledged. Only a real semantic change can unseal automatically.
        if (tracked.blocked || tracked.unsupported) {
            val semanticChange = when {
                cancelObserved -> tracked.cancelledObservedAtMs == null
                completeObserved -> tracked.completionObservedAtMs == null
                observation.runState == LaundryRunState.RUNNING -> expectedChanged
                else -> false
            }
            if (!semanticChange) return tracked
        }

        // Terminal observations are sticky immediately, not only after ACK.
        if (tracked.completionObservedAtMs != null || tracked.cancelledObservedAtMs != null)
            return tracked
        if (tracked.completionAcked || tracked.cancelledAcked) return tracked

        if (cancelObserved) {
            return tracked.copy(
                cancelledObservedAtMs = observation.observedAtMs,
                cancelledAcked = false,
                completionObservedAtMs = null,
                completionAcked = false,
                blocked = false,
                unsupported = false
            )
        }
        if (completeObserved) {
            return tracked.copy(
                completionObservedAtMs = observation.observedAtMs,
                completionAcked = false,
                blocked = false,
                unsupported = false
            )
        }
        if (observation.runState != LaundryRunState.RUNNING) return tracked

        // Same state + same original estimate is a no-op even when running is still unacked.
        // This preserves the first observed_at_ms and never clears a previous failure on a poll.
        if (!expectedChanged) return tracked
        // An older observation must not replace a newer estimate/observation.
        if (observation.observedAtMs < tracked.runningObservedAtMs) return tracked
        return tracked.copy(
            runningObservedAtMs = observation.observedAtMs,
            runningExpectedEndAtMs = expected,
            runningAcked = false,
            blocked = false,
            unsupported = false
        )
    }

    /** Running must be acknowledged before a completion/cancellation for an offline transition. */
    fun nextReport(record: CloudDeviceReportRecord?, force: Boolean = false):
        CloudOrderReport? {
        if (record == null) return null
        if (!force && (record.blocked || record.unsupported)) return null
        return when {
            !record.runningAcked -> CloudOrderReport(
                orderId = record.orderId,
                orderNo = record.orderNo,
                state = "running",
                observedAtMs = record.runningObservedAtMs,
                expectedEndAtMs = record.runningExpectedEndAtMs
            )
            record.cancelledObservedAtMs != null && !record.cancelledAcked -> CloudOrderReport(
                orderId = record.orderId,
                orderNo = record.orderNo,
                state = "cancelled",
                observedAtMs = record.cancelledObservedAtMs,
                expectedEndAtMs = null
            )
            record.completionObservedAtMs != null && !record.completionAcked -> CloudOrderReport(
                orderId = record.orderId,
                orderNo = record.orderNo,
                state = "completed",
                observedAtMs = record.completionObservedAtMs,
                expectedEndAtMs = null
            )
            else -> null
        }
    }

    fun pendingState(record: CloudDeviceReportRecord?): String? = nextReport(record)?.state

    /**
     * Root ACK/failure CAS helper: true only for the exact report that is still pending for this
     * record (strong identity, state, observed_at_ms and expected_end_at_ms all match).
     */
    fun isCurrentReport(record: CloudDeviceReportRecord, report: CloudOrderReport): Boolean {
        val current = nextReport(record) ?: return false
        return sameStrongIdentity(current, report) &&
            current.state == report.state &&
            current.observedAtMs == report.observedAtMs &&
            current.expectedEndAtMs == report.expectedEndAtMs
    }

    /** Prefer order_id when both sides have it; fall back to order_no otherwise. */
    fun sameStrongIdentity(a: CloudOrderReport, b: CloudOrderReport): Boolean {
        val aId = a.orderId.trim()
        val bId = b.orderId.trim()
        if (aId.isNotBlank() && bId.isNotBlank()) return aId == bId
        return a.orderNo.isNotBlank() && a.orderNo.trim() == b.orderNo.trim()
    }

    /** Strong-ID match for merging a status readback into an encrypted tracked record. */
    fun matchesSubscription(record: CloudDeviceReportRecord,
        subscription: CloudSubscriptionSummary
    ): Boolean {
        val recordId = record.orderId.trim()
        val subId = subscription.orderId.orEmpty().trim()
        if (recordId.isNotBlank() && subId.isNotBlank()) return recordId == subId
        // One side missing the strong id: use the number fallback rather than guessing.
        val subNo = subscription.orderNo.orEmpty().trim()
        return record.orderNo.isNotBlank() && record.orderNo.trim() == subNo
    }

    fun acknowledged(record: CloudDeviceReportRecord, report: CloudOrderReport,
        subscription: CloudSubscriptionSummary
    ): CloudDeviceReportRecord {
        // A stale/older payload must not mark a newer estimate or a different event acknowledged.
        if (!isCurrentReport(record, report)) return record
        val running = report.state == "running"
        val completed = report.state == "completed"
        val cancelled = report.state == "cancelled"
        return record.copy(
            runningAcked = record.runningAcked || running,
            completionAcked = record.completionAcked || completed,
            cancelledAcked = record.cancelledAcked || cancelled,
            lastReportedState = report.state,
            lastReportedObservedAtMs = report.observedAtMs,
            lastReportedExpectedEndAtMs = report.expectedEndAtMs,
            serverState = subscription.state,
            serverSource = subscription.source,
            serverClientState = subscription.clientState,
            completionSource = subscription.completionSource,
            estimatedDeliveryState = subscription.estimatedDeliveryState,
            estimatedAccepted = subscription.estimatedAccepted,
            estimatedDelivered = subscription.estimatedDelivered,
            lastIssue = null,
            reasonCode = null,
            blocked = false,
            unsupported = false
        )
    }

    fun transientFailure(record: CloudDeviceReportRecord, issue: String,
        reasonCode: String? = null
    ): CloudDeviceReportRecord = record.copy(
        lastIssue = issue,
        reasonCode = CloudUpstream.sanitize(reasonCode),
        blocked = false,
        unsupported = false
    )

    fun terminalFailure(record: CloudDeviceReportRecord, issue: String,
        reasonCode: String? = null
    ): CloudDeviceReportRecord = record.copy(
        lastIssue = issue,
        reasonCode = CloudUpstream.sanitize(reasonCode),
        blocked = true
    )

    fun unsupportedFailure(record: CloudDeviceReportRecord): CloudDeviceReportRecord =
        record.copy(lastIssue = "unsupported", reasonCode = null, blocked = true, unsupported = true)

    /** Manual retry clears a previous terminal/unsupported stop before one fresh attempt. */
    fun prepareForced(record: CloudDeviceReportRecord): CloudDeviceReportRecord =
        record.copy(blocked = false, unsupported = false, lastIssue = null, reasonCode = null)

    fun isTracked(record: CloudDeviceReportRecord?, orderKey: String): Boolean =
        record != null && record.orderKey == orderKey &&
            (record.runningAcked || record.completionObservedAtMs != null ||
                record.cancelledObservedAtMs != null)

    private fun usableExpected(expected: Long?, observedAtMs: Long): Long? =
        expected?.takeIf { it > observedAtMs && it - observedAtMs <= MAX_EXPECTED_AHEAD_MS }
}
