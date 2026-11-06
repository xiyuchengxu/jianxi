package dev.rain.laundry

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.rain.laundry.core.*
import dev.rain.laundry.data.SessionVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local vault records only; these tests never create an API client or payment request. */
@RunWith(AndroidJUnit4::class)
class OrderHistoryRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyCurrentAndWaterBillMigrateWithoutDuplicateOrStaleSettlementState() = withVault { vault ->
        vault.login("history-local-owner-a", "local-fixture-token")
        val owner = vault.owner()
        val legacy = record(owner, 1).copy(phase = OrderPhase.CREATING, orderId = "", orderNo = "")
        // Reproduce the pre-history encrypted single-record format rather than new saveOrder().
        val put = SessionVault::class.java.getDeclaredMethod("put", String::class.java, String::class.java)
            .apply { isAccessible = true }
        put.invoke(vault, "order_$owner", Gson().toJson(legacy))
        val migrated = SessionVault(context)
        assertEquals(listOf(legacy), migrated.orders())
        assertEquals(legacy, migrated.order())
        val complete = legacy.copy(orderId = "legacy-id", orderNo = "legacy-number", phase = OrderPhase.COMPLETED)
        migrated.saveOrder(complete)
        assertEquals(listOf(complete), SessionVault(context).orders())

        val bill = record(owner, 2, water = true)
        val outlet = WaterOutlet("fixture-outlet", "常温水")
        val device = WaterDevice("fixture-water", "本地测试饮水机", "LOCAL-IMEI", "shop", "org", listOf(outlet), true)
        val session = WaterSession(owner, device, outlet, WaterPhase.ENDED, startedAt = bill.createdAt, settlementOrder = bill)
        migrated.saveWater(session)
        assertEquals(listOf(bill, complete), migrated.orders())
        val settledBill = bill.copy(phase = OrderPhase.COMPLETED)
        migrated.saveOrder(settledBill)
        // An old embedded WaterSession snapshot must not revert the saved bill's completed state.
        migrated.saveWater(session)
        assertEquals(listOf(settledBill, complete), SessionVault(context).orders())
        val encrypted = context.getSharedPreferences("laundry_vault", 0).getString("orders_$owner", "").orEmpty()
        assertFalse(encrypted.contains("legacy-number"))
        assertFalse(encrypted.contains("fixture-number-2"))
    }

    @Test fun historyLimitNeverDropsOrOverwritesTheSeparateUnfinishedCurrentPointer() = withVault { vault ->
        vault.login("history-local-owner-a", "local-fixture-token")
        val owner = vault.owner()
        val unfinished = record(owner, 0).copy(phase = OrderPhase.UNCONFIRMED, orderId = "", orderNo = "")
        vault.saveOrder(unfinished)
        (1..25).forEach { vault.saveOrder(record(owner, it, water = it % 2 == 0).copy(phase = OrderPhase.COMPLETED)) }
        val history = SessionVault(context).orders()
        assertEquals((25 downTo 6).map(Int::toLong), history.map { it.createdAt })
        assertEquals(10, history.count { it.isWater })
        assertEquals(10, history.count { !it.isWater })
        assertFalse(history.any { OrderHistory.sameOrder(it, unfinished) })
        assertEquals(unfinished, SessionVault(context).order())

        val refreshed = history.single { it.orderId == "fixture-id-10" }.copy(phase = OrderPhase.REFUNDED)
        vault.saveOrder(refreshed)
        assertEquals(20, vault.orders().size)
        assertEquals(refreshed, vault.orders().single { it.orderId == "fixture-id-10" })
        assertEquals(unfinished, vault.order())
        assertClientError { vault.selectOrder(history.first()) }
        assertEquals(unfinished, vault.order())
        vault.selectOrder(unfinished)
        assertEquals(unfinished, vault.order())
    }

    @Test fun historyAndCurrentOrderRemainIsolatedWhenAccountsSwitchOrLogout() = withVault { vault ->
        vault.login("history-local-owner-a", "local-fixture-token-a")
        val ownerA = vault.owner()
        (1..22).forEach { vault.saveOrder(record(ownerA, it).copy(phase = OrderPhase.COMPLETED)) }
        val ordersA = vault.orders()
        assertEquals(20, ordersA.size)
        vault.login("history-local-owner-b", "local-fixture-token-b")
        val ownerB = vault.owner()
        assertNotEquals(ownerA, ownerB)
        assertTrue(vault.orders().isEmpty())
        assertNull(vault.order())
        val orderB = record(ownerB, 30, water = true)
        vault.saveOrder(orderB)
        assertEquals(listOf(orderB), vault.orders())
        assertClientError { vault.selectOrder(ordersA.first()) }
        assertEquals(orderB, vault.order())
        vault.logout()
        assertTrue(vault.orders().isEmpty())
        assertNull(vault.order())
        vault.login("history-local-owner-a", "local-fixture-token-a")
        assertEquals(ordersA, SessionVault(context).orders())
        assertTrue(vault.orders().all { it.owner == ownerA })
        assertEquals(ordersA.first(), vault.order())
        vault.login("history-local-owner-b", "local-fixture-token-b")
        assertEquals(listOf(orderB), vault.orders())
        assertEquals(orderB, vault.order())
    }

    private fun record(owner: String, index: Int, water: Boolean = false) = LocalOrder(
        owner, "本地测试设备 $index", "测试模式", "1.00", PayMethod.ALIPAY, OrderPhase.CREATED,
        createdAt = index.toLong(), orderId = "fixture-id-$index", orderNo = "fixture-number-$index",
        category = if (water) "04" else null
    )

    private fun assertClientError(block: () -> Unit) {
        try {
            block()
            fail("Expected a current-order or owner conflict to reject promotion")
        } catch (_: ClientError) {
            // Expected; the caller cannot replace an unrelated unfinished order or another account's order.
        }
    }

    private fun withVault(block: (SessionVault) -> Unit) {
        val preferences = context.getSharedPreferences("laundry_vault", 0)
        val previous = preferences.all.toMap()
        assertTrue(preferences.edit().clear().commit())
        try { block(SessionVault(context)) }
        finally { restore(preferences, previous) }
    }

    private fun restore(preferences: SharedPreferences, previous: Map<String, *>) {
        val editor = preferences.edit().clear()
        previous.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(editor.commit())
    }
}
