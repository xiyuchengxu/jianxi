package dev.rain.laundry.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.WireProtocol
import dev.rain.laundry.data.SessionVault
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Keystore records only; no account, server or network request is used. */
@RunWith(AndroidJUnit4::class)
class CloudReminderRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("cloud_reminder_vault", 0)
    private val ownerA = "cloud-owner-a"
    private val ownerB = "cloud-owner-b"

    @Before fun clear() {
        prefs.edit().clear().commit()
        SessionVault(context).logout()
    }

    @After fun cleanup() {
        prefs.edit().clear().commit()
        SessionVault(context).logout()
    }

    @Test fun bindingAndBaseUrlAreEncryptedPerOwnerAndNeverReusedAcrossAccounts() {
        val vault = CloudReminderVault(context)
        vault.saveBinding(ownerA, binding(ownerA))
        assertNotNull(vault.binding(ownerA))
        assertNull("another account must not inherit the binding", vault.binding(ownerB))
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertFalse(raw.contains("https://reminder.example"))
        assertFalse(raw.contains("device-token-secret"))
        assertFalse(raw.contains("account-secret"))
        assertFalse(prefs.all.keys.any { it.contains(ownerA) })
        assertNotNull(vault.loadHashed(WireProtocol.sha256(ownerA)))
        assertNull(vault.loadHashed(WireProtocol.sha256(ownerB)))
    }

    @Test fun logoutDetachKeepsOnlyAnEncryptedIsolatedUnbindRetry() {
        val vault = CloudReminderVault(context)
        vault.saveBinding(ownerA, binding(ownerA))
        val pending = vault.detachForLogout(ownerA)
        assertNotNull(pending)
        assertNull(vault.binding(ownerA))
        assertEquals(1, vault.pendingUnbinds(ownerA).size)
        assertEquals(0, vault.pendingUnbinds(ownerB).size)
        assertEquals(1, vault.pendingUnbindsHashed(WireProtocol.sha256(ownerA)).size)
        assertEquals(0, vault.pendingUnbindsHashed(WireProtocol.sha256(ownerB)).size)
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertFalse(raw.contains("device-token-secret"))
        assertFalse(raw.contains("https://reminder.example"))
        vault.removePendingUnbind(ownerA, pending!!.deviceId)
        assertTrue(vault.pendingUnbinds(ownerA).isEmpty())
    }

    @Test fun pairingCodeAndMiaotixingCodeAreNotPartOfTheStoredSchema() {
        val vault = CloudReminderVault(context)
        vault.saveBinding(ownerA, binding(ownerA).copy(notificationConfigured = true))
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        // These values are supplied only at request time by the UI and never reach this vault.
        assertFalse(raw.contains("pairing-code"))
        assertFalse(raw.contains("miaotixing-code"))
        assertTrue(vault.binding(ownerA)?.notificationConfigured == true)
    }

    @Test fun terminalRejectionAndItsAttemptCountSurviveTheEncryptedRoundTrip() {
        val vault = CloudReminderVault(context)
        vault.saveBinding(ownerA, binding(ownerA))
        val record = CloudOrderSyncRecord("order-key", "order-id", "order-no",
            CloudOrderSyncState.REJECTED, 1_900_000_000_000L, "http_502", attempts = 3,
            reasonCode = "upstream_http_405")
        vault.putOrderRecord(ownerA, record)
        val loaded = vault.orderRecord(ownerA, "order-key")
        assertEquals(CloudOrderSyncState.REJECTED, loaded?.state)
        assertEquals("http_502", loaded?.lastIssue)
        assertEquals("upstream_http_405", loaded?.reasonCode)
        assertEquals(3, loaded?.attempts)
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertFalse(raw.contains("order-id"))
        assertFalse(raw.contains("order-no"))
        assertFalse(raw.contains("http_502"))
        assertFalse(raw.contains("upstream_http_405"))
    }

    @Test fun deviceAssistedBindingAndRunningEvidenceRoundTripEncrypted() {
        val vault = CloudReminderVault(context)
        vault.saveBinding(ownerA, binding(ownerA).copy(deviceAssisted = true))
        val record = CloudDeviceReportRecord("order-key", "order-id", "order-no",
            runningObservedAtMs = 1_900_000_000_000L,
            runningExpectedEndAtMs = 1_900_000_180_000L,
            runningAcked = false,
            serverSource = "device_report",
            estimatedAccepted = true,
            estimatedDelivered = false)
        vault.putDeviceReport(ownerA, record)
        assertTrue(vault.binding(ownerA)?.deviceAssisted == true)
        val loaded = vault.deviceReport(ownerA, "order-key")
        assertEquals(1_900_000_000_000L, loaded?.runningObservedAtMs)
        assertEquals(1_900_000_180_000L, loaded?.runningExpectedEndAtMs)
        assertEquals(false, loaded?.runningAcked)
        assertEquals("device_report", loaded?.serverSource)
        assertTrue(loaded?.estimatedAccepted == true)
        assertFalse(loaded?.estimatedDelivered == true)
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertFalse(raw.contains("order-id"))
        assertFalse(raw.contains("order-no"))
        assertFalse(raw.contains("device_report"))
    }

    @Test fun modeSwitchInvalidatesOldBindingCasAndUsesSeparateReportStore() {
        val vault = CloudReminderVault(context)
        val old = binding(ownerA)
        vault.saveBinding(ownerA, old)
        assertTrue(vault.compareAndSetBinding(ownerA, old, old.copy(deviceAssisted = true)))
        assertFalse(vault.compareAndSetBinding(ownerA, old,
            old.copy(notificationConfigured = true)))
        val report = CloudDeviceReportRecord("report-key", "report-id", "report-no",
            runningObservedAtMs = 1_900_000_000_000L,
            runningExpectedEndAtMs = 1_900_000_180_000L)
        vault.putDeviceReport(ownerA, report)
        assertEquals("report-key", vault.deviceReport(ownerA, "report-key")?.orderKey)
        assertTrue(vault.orderRecords(ownerA).isEmpty())
        val assisted = requireNotNull(vault.binding(ownerA))
        assertTrue(vault.compareAndSetBinding(ownerA, assisted, null, emptyMap(), emptyMap()))
        assertTrue(vault.deviceReportRecords(ownerA).isEmpty())
    }

    private fun binding(owner: String) = CloudBinding(
        owner = owner,
        serverUrl = "https://reminder.example",
        deviceToken = "device-token-secret",
        accountId = "account-secret",
        deviceId = "device-secret",
        deviceLabel = "Pixel test",
        notificationConfigured = false,
        consentToReadOnlyQuery = true
    )
}
