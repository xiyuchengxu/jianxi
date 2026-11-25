package dev.rain.laundry.cloud

import com.google.gson.Gson
import dev.rain.laundry.core.LaundryProgress
import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.LocalOrder
import dev.rain.laundry.core.OrderPhase
import dev.rain.laundry.core.PayMethod
import dev.rain.laundry.core.RemoteOrderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReminderPolicyTest {
    private val now = 1_900_000_000_000L

    @Test fun onlyPaidRunningOrdinaryLaundryWithIdentityIsEligible() {
        val paid = order(OrderPhase.PAID)
        assertTrue(CloudReminderPolicy.eligible(paid, now))
        assertFalse(CloudReminderPolicy.eligible(paid.copy(phase = OrderPhase.CREATED), now))
        assertFalse(CloudReminderPolicy.eligible(paid.copy(phase = OrderPhase.PAYMENT_PENDING), now))
        assertFalse(CloudReminderPolicy.eligible(paid.copy(phase = OrderPhase.COMPLETED), now))
        assertFalse(CloudReminderPolicy.eligible(paid.copy(phase = OrderPhase.REFUNDED), now))
        assertFalse(CloudReminderPolicy.eligible(paid.copy(category = "04"), now))
        assertFalse(CloudReminderPolicy.eligible(paid.copy(orderId = "", orderNo = ""), now))
        assertFalse(CloudReminderPolicy.eligible(paid.copy(createdAt = now - 25 * 60 * 60 * 1000L), now))
    }

    @Test fun aConfirmedFinishedSnapshotIsNotSubscribedEvenIfPhaseLooksPaid() {
        val finished = LaundryProgress("id", "no", orderStatus = RemoteOrderStatus.COMPLETED,
            updatedAt = now)
        assertEquals(LaundryRunState.FINISHED, finished.runState)
        assertFalse(CloudReminderPolicy.eligible(order(OrderPhase.PAID).copy(laundryProgress = finished), now))
    }

    @Test fun singleSelectionPrefersCurrentAndNeverFansOutHistory() {
        val older = order(OrderPhase.PAID, id = "old", no = "old-no", at = now - 120_000)
        val current = order(OrderPhase.PAID, id = "current", no = "current-no", at = now - 60_000)
        assertEquals("current", CloudReminderPolicy.selectSingle(listOf(older, current), current, now)?.orderId)
        assertEquals("current", CloudReminderPolicy.selectSingle(listOf(older, current), null, now)?.orderId)
        assertNull(CloudReminderPolicy.selectSingle(listOf(older),
            current.copy(phase = OrderPhase.COMPLETED), now))
        assertNull(CloudReminderPolicy.selectSingle(listOf(older.copy(category = "04")), null, now))
    }

    @Test fun userVisibleStatesAreExplicitAndAuthFailureIsNotSilentlyHealthy() {
        assertEquals(CloudReminderStatus.DISABLED,
            CloudReminderStateRules.status(bound = false, issue = CloudIssue(CloudIssueKind.NETWORK)))
        assertEquals(CloudReminderStatus.BOUND,
            CloudReminderStateRules.status(bound = true, accountStatus = "active"))
        assertEquals(CloudReminderStatus.PENDING,
            CloudReminderStateRules.status(bound = true, pending = true))
        assertEquals(CloudReminderStatus.PENDING,
            CloudReminderStateRules.status(bound = true, issue = CloudIssue(CloudIssueKind.NETWORK), pending = true))
        assertEquals(CloudReminderStatus.ERROR,
            CloudReminderStateRules.status(bound = true, issue = CloudIssue(CloudIssueKind.UNBIND_FAILED)))
        assertEquals(CloudReminderStatus.AUTH_REQUIRED,
            CloudReminderStateRules.status(bound = true, accountStatus = "auth_required"))
        assertEquals(CloudReminderStatus.AUTH_REQUIRED,
            CloudReminderStateRules.status(bound = true, issue = CloudIssue(CloudIssueKind.AUTH_REQUIRED)))
    }

    @Test fun providerAcceptanceNeverBecomesDeliveryInTheUiModel() {
        val status = CloudSubscriptionSummary("s", "o", "n", "completed", "accepted",
            accepted = true, delivered = false)
        assertTrue(status.accepted)
        assertFalse(status.delivered)
    }

    @Test fun onlyTransportThrottleAndServerFailuresAreAutomaticallyRetryable() {
        for (code in listOf(408, 425, 429, 500, 502, 503, 504)) {
            assertEquals("HTTP $code", CloudFailureClass.TRANSIENT,
                CloudSyncFailurePolicy.classifyHttp(code))
        }
        for (code in listOf(400, 404, 409, 422)) {
            assertEquals("HTTP $code", CloudFailureClass.TERMINAL,
                CloudSyncFailurePolicy.classifyHttp(code))
        }
        assertEquals(CloudFailureClass.AUTH_REQUIRED,
            CloudSyncFailurePolicy.classifyHttp(401))
        assertEquals(CloudFailureClass.AUTH_REQUIRED,
            CloudSyncFailurePolicy.classifyHttp(403))
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(307))
        assertEquals(CloudFailureClass.TRANSIENT,
            CloudSyncFailurePolicy.classifyHttp(502, retryableHint = true))
        assertEquals(CloudFailureClass.TRANSIENT,
            CloudSyncFailurePolicy.classifyHttp(502))
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(502, retryableHint = false))
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(502, false, "upstream_http_405"))
        assertEquals("405", CloudUpstream.displayStatus("upstream_http_405"))
        assertEquals(CloudFailureClass.TRANSIENT,
            CloudSyncFailurePolicy.classifyHttp(502, true, "upstream_unavailable"))
        assertEquals(CloudFailureClass.TRANSIENT,
            CloudSyncFailurePolicy.classifyHttp(502, true, "upstream_http_5xx"))
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(502, null, "upstream_invalid_response"))
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(502, null, "identity_mismatch"))
        assertEquals(CloudFailureClass.AUTH_REQUIRED,
            CloudSyncFailurePolicy.classifyHttp(502, null, "upstream_auth_required"))
        assertTrue(CloudSyncFailurePolicy.mayRetryNotRunning(0))
        assertTrue(CloudSyncFailurePolicy.mayRetryNotRunning(2))
        assertFalse(CloudSyncFailurePolicy.mayRetryNotRunning(3))
        assertFalse(CloudSyncFailurePolicy.shouldDeferNotRunning(409, false, 1))
        assertTrue(CloudSyncFailurePolicy.shouldDeferNotRunning(409, null, 1))
        assertTrue(CloudSyncFailurePolicy.shouldDeferNotRunning(409, true, 1))
        assertFalse(CloudSyncFailurePolicy.shouldDeferNotRunning(409, null, 3))
        for (code in CloudUpstream.ALLOWED) assertEquals(code, code, CloudUpstream.sanitize(code))
        assertNull(CloudUpstream.sanitize("upstream_http_405<script>"))
        assertNull(CloudUpstream.sanitize("totally_unknown"))
    }

    @Test fun terminalRejectionIsVisibleAsErrorAndKeepsASanitizedHttpDiagnostic() {
        val record = CloudOrderSyncRecord("key", "id-1", "no-1",
            CloudOrderSyncState.REJECTED, now, "http_502", reasonCode = "upstream_http_405")
        val issue = CloudReminderStateRules.rejectedIssue(listOf(record))
        assertEquals(CloudIssueKind.SUBSCRIPTION_REJECTED, issue?.kind)
        assertEquals("502", issue?.detail)
        assertEquals("upstream_http_405", issue?.reasonCode)
        assertEquals("405", CloudUpstream.displayStatus(issue?.reasonCode))
        assertEquals(CloudReminderStatus.ERROR,
            CloudReminderStateRules.status(bound = true, pending = false, issue = issue))
        assertEquals(CloudReminderStatus.ERROR,
            CloudReminderStateRules.status(bound = true, pending = true, issue = issue))
        assertNull(CloudReminderStateRules.rejectedIssue(listOf(
            record.copy(reasonCode = "upstream_http_405<script>alert(1)</script>")))?.reasonCode)
        assertNull(CloudSyncFailurePolicy.httpCode("网络不可用"))
        assertNull(CloudSyncFailurePolicy.httpCode(null))
        assertNull(CloudReminderStateRules.rejectedIssue(emptyList()))
    }

    @Test fun pendingHttpFailureKeepsItsStatusForDiagnostics() {
        val pending = CloudOrderSyncRecord("key", "id-1", "no-1",
            CloudOrderSyncState.PENDING, now, "http_502", reasonCode = "upstream_unavailable")
        val issue = CloudReminderStateRules.pendingIssue(listOf(pending))
        assertEquals(CloudIssueKind.SYNC_PENDING, issue?.kind)
        assertEquals("502", issue?.detail)
        assertEquals("upstream_unavailable", issue?.reasonCode)
        assertEquals(CloudReminderStatus.PENDING,
            CloudReminderStateRules.status(bound = true, pending = true, issue = issue))
        assertNull(CloudReminderStateRules.pendingIssue(listOf(
            pending.copy(lastIssue = "network", reasonCode = null))))
    }

    @Test fun localKeyUsesExistingBoundedOrderIdentity() {
        val first = order(OrderPhase.PAID)
        assertEquals(dev.rain.laundry.core.OrderHistory.key(first),
            CloudReminderPolicy.eligibleOrder(first, now)?.orderKey)
    }

    @Test fun legacyBindingJsonDefaultsDeviceAssistedFalseWithoutRebinding() {
        val legacy = Gson().fromJson("""
            {"owner":"owner-a","serverUrl":"https://reminder.example",
             "deviceToken":"device-token","accountId":"account-a","deviceId":"device-a",
             "deviceLabel":"Phone","notificationConfigured":false,
             "accountStatus":"active","consentToReadOnlyQuery":true}
        """.trimIndent(), CloudBinding::class.java)
        assertFalse(legacy.deviceAssisted)
        assertTrue(legacy.copy(deviceAssisted = true).deviceAssisted)
    }

    private fun order(phase: OrderPhase, id: String = "id-1", no: String = "no-1",
        at: Long = now - 60_000) = LocalOrder("owner-a", "washer", "standard", "4.00",
        PayMethod.ALIPAY, phase, createdAt = at, orderId = id, orderNo = no)
}
