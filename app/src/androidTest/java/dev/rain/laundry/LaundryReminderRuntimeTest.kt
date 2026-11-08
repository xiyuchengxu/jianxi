package dev.rain.laundry

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.LocalOrder
import dev.rain.laundry.core.OrderPhase
import dev.rain.laundry.core.PayMethod
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.notifications.LaundryReminderDelivery
import dev.rain.laundry.notifications.LaundryReminders
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic encrypted records only; notification delivery is injected and no HTTP client is created. */
@RunWith(AndroidJUnit4::class)
class LaundryReminderRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_800_000_000_000L

    @Test fun onlyAnObservedRunningCycleCanNotifyAfterServerCompletion() = withVault { vault ->
        vault.login("reminder-owner-a", "local-fixture-token")
        val order = order(vault.owner())
        val delivery = FakeDelivery()
        observe(vault, order.copy(phase = OrderPhase.COMPLETED), false, true, delivery)
        assertTrue(vault.laundryReminders().isEmpty())
        observe(vault, order, false, false, delivery)
        assertTrue("A payment acknowledgement must not arm a running cycle", vault.laundryReminders().isEmpty())
        observe(vault, order.copy(category = "04"), true, false, delivery)
        assertTrue("Water settlement must never generate a laundry reminder", vault.laundryReminders().isEmpty())

        observe(vault, order, true, false, delivery, now - 60_000)
        assertEquals(1, vault.laundryReminders().size)
        observe(vault, order, true, false, delivery, now - 60_000)
        assertEquals("An elapsed estimate is not authoritative completion", 0, delivery.posted.get())
        assertFalse(vault.laundryReminders().single().notificationClaimed)
        assertTrue(observe(vault, order.copy(phase = OrderPhase.COMPLETED), false, true, delivery))
        assertEquals(1, delivery.posted.get())
        assertTrue(SessionVault(context).laundryReminders().single().notificationClaimed)
        assertFalse(observe(SessionVault(context), order.copy(phase = OrderPhase.COMPLETED), false, true, delivery))
        assertEquals(1, delivery.posted.get())
    }

    @Test fun deniedPermissionKeepsProgressAndCanDeliverOnceAfterPermissionIsGranted() = withVault { vault ->
        vault.login("reminder-owner-a", "local-fixture-token")
        val order = order(vault.owner())
        val delivery = FakeDelivery(allowed = false)
        observe(vault, order, true, false, delivery, now + 30_000)
        assertFalse(observe(vault, order.copy(phase = OrderPhase.COMPLETED), false, true, delivery))
        val completed = vault.laundryReminders().single()
        assertNotNull(completed.completedAtMs)
        assertFalse(completed.notificationClaimed)
        assertEquals(0, delivery.posted.get())
        delivery.allowed = true
        assertTrue(LaundryReminders.deliver(vault, completed, now, delivery))
        assertFalse(LaundryReminders.deliver(vault, completed, now, delivery))
        assertEquals(1, delivery.posted.get())
        val raw = context.getSharedPreferences("laundry_vault", 0)
            .getString("laundry_reminders_${order.owner}", "").orEmpty()
        assertFalse("Reminder records must use the encrypted vault", raw.contains("fixture-laundry-number"))
    }

    @Test fun logoutAndAccountSwitchCannotApplyOrDeliverThePreviousAccountsResult() = withVault { vault ->
        vault.login("reminder-owner-a", "local-fixture-token")
        val order = order(vault.owner())
        val delivery = FakeDelivery()
        observe(vault, order, true, false, delivery)
        vault.login("reminder-owner-b", "other-local-token")
        assertFalse(observe(vault, order.copy(phase = OrderPhase.COMPLETED), false, true, delivery))
        assertTrue(vault.laundryReminders().isEmpty())
        vault.login("reminder-owner-a", "local-fixture-token")
        val switchingDelivery = object : LaundryReminderDelivery {
            override fun canPost(): Boolean { vault.login("reminder-owner-b", "other-local-token"); return true }
            override fun post(order: LocalOrder) { fail("Account changed immediately before delivery") }
        }
        assertFalse(observe(vault, order.copy(phase = OrderPhase.COMPLETED), false, true, switchingDelivery))
        vault.login("reminder-owner-a", "local-fixture-token")
        assertFalse(vault.laundryReminders().single().notificationClaimed)
        vault.logout()
        assertFalse(observe(vault, order.copy(phase = OrderPhase.COMPLETED), false, true, delivery))
        assertEquals(0, delivery.posted.get())
    }

    @Test fun workerAndForegroundCompletionRaceSharesOnePersistentDeliveryClaim() = withVault { vault ->
        vault.login("reminder-owner-a", "local-fixture-token")
        val order = order(vault.owner())
        val delivery = FakeDelivery()
        observe(vault, order, true, false, delivery)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<Boolean> {
                    start.await()
                    observe(SessionVault(context), order.copy(phase = OrderPhase.COMPLETED), false, true, delivery)
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, delivery.posted.get())
            assertTrue(SessionVault(context).laundryReminders().single().notificationClaimed)
        } finally { executor.shutdownNow() }
    }

    @Test fun staleSnapshotsAndEarlierLoginTokensCannotOverwriteNewForegroundState() = withVault { vault ->
        vault.login("reminder-owner-a", "first-local-token")
        val order = order(vault.owner())
        vault.saveOrder(order)
        val completed = order.copy(phase = OrderPhase.COMPLETED)
        assertFalse(vault.updateLaundryOrder(order.owner, "first-local-token", order, completed) { false })
        assertEquals(order, vault.order())
        val refunded = order.copy(phase = OrderPhase.REFUNDED)
        vault.saveOrder(refunded)
        assertFalse(vault.updateLaundryOrder(order.owner, "first-local-token", order, completed))
        assertEquals(refunded, vault.order())
        vault.login("reminder-owner-a", "second-local-token")
        assertFalse(vault.updateLaundryOrder(order.owner, "first-local-token", refunded, completed))
        assertEquals(refunded, vault.order())
    }

    @Test fun anArmedCycleSurvivesHistoryTruncationWithoutExpandingTwentyOrderRecords() = withVault { vault ->
        vault.login("reminder-owner-a", "local-fixture-token")
        val order = order(vault.owner())
        vault.saveOrder(order)
        observe(vault, order, true, false, FakeDelivery())
        (1..25).forEach { index ->
            vault.saveOrder(order.copy(orderId = "new-id-$index", orderNo = "new-no-$index",
                createdAt = now + index, phase = OrderPhase.COMPLETED))
        }
        val history = vault.orders()
        val current = vault.order()
        assertEquals(20, history.size)
        assertFalse(history.any { it.orderId == order.orderId })
        assertTrue(vault.updateLaundryOrder(order.owner, "local-fixture-token", order,
            order.copy(phase = OrderPhase.COMPLETED)))
        assertEquals(history, vault.orders())
        assertEquals(current, vault.order())
        assertEquals(OrderPhase.COMPLETED, vault.laundryReminders().single().order.phase)
    }

    private fun observe(vault: SessionVault, order: LocalOrder, running: Boolean, completed: Boolean,
        delivery: LaundryReminderDelivery, end: Long? = null
    ) = LaundryReminders.observe(vault, order, running, completed, end, now, delivery)

    private fun order(owner: String) = LocalOrder(owner, "本地测试洗衣机", "标准洗", "1.00",
        PayMethod.ALIPAY, OrderPhase.PAID, createdAt = now - 60_000,
        orderId = "fixture-laundry-id", orderNo = "fixture-laundry-number")

    private class FakeDelivery(var allowed: Boolean = true) : LaundryReminderDelivery {
        val posted = AtomicInteger()
        override fun canPost() = allowed
        override fun post(order: LocalOrder) { posted.incrementAndGet() }
    }

    private fun withVault(block: (SessionVault) -> Unit) {
        val preferences = context.getSharedPreferences("laundry_vault", 0)
        val previous = preferences.all.toMap()
        assertTrue(preferences.edit().clear().commit())
        try { block(SessionVault(context)) } finally { restore(preferences, previous) }
    }

    private fun restore(preferences: SharedPreferences, previous: Map<String, *>) {
        val editor = preferences.edit().clear()
        previous.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(editor.commit())
    }
}
