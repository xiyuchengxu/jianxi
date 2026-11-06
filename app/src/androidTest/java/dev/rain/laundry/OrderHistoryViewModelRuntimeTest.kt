package dev.rain.laundry

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.*
import dev.rain.laundry.data.LaundryApi
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Every HTTP request is intercepted; only synthetic local orders are used. */
@RunWith(AndroidJUnit4::class)
class OrderHistoryViewModelRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun refreshingHistoryKeepsTheUnfinishedPointerAndExplicitActionsTargetTheClickedOrder() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val preferences = context.getSharedPreferences("laundry_vault", 0)
        val previous = preferences.all.toMap()
        assertTrue(preferences.edit().clear().commit())
        val store = ViewModelStore()
        val queriedIds = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath == "/order/detail") { "Unexpected operation ${request.url.encodedPath}" }
            val body = request.body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            val id = fields["orderId"].orEmpty()
            check(id in setOf("current-a", "old-b", "pending-c")) { "Unexpected local target $id" }
            queriedIds += id
            val remoteStatus = if (id == "old-b") 5 else 1
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture")
                .body("""{"code":0,"data":{"id":"$id","orderStatus":$remoteStatus}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val vault = SessionVault(context).apply { login("history-viewmodel-local-owner", "fixture-token") }
            val owner = vault.owner()
            val current = order(owner, "current-a", 20, OrderPhase.UNCONFIRMED)
            val old = order(owner, "old-b", 10, OrderPhase.COMPLETED)
            val pending = order(owner, "pending-c", 30, OrderPhase.CREATED)
            vault.saveOrder(current)
            vault.saveOrder(old)
            vault.saveOrder(pending)
            lateinit var vm: LaundryViewModel
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[LaundryViewModel::class.java]
                val api = LaundryViewModel::class.java.getDeclaredField("api").apply { isAccessible = true }.get(vm) as LaundryApi
                LaundryApi::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, client)
                vm.refreshOrder(old)
            }
            await { !vm.state.value.busy && vm.state.value.orderHistory.any { it.orderId == old.orderId && it.phase == OrderPhase.REFUNDED } }
            assertEquals(current, vm.state.value.order)
            assertEquals(current, SessionVault(context).order())
            assertEquals(listOf("old-b"), queriedIds.toList())

            instrumentation.runOnMainSync { vm.resumePayment(pending) }
            assertEquals("请先处理当前未完成的订单", vm.state.value.message)
            assertEquals(current, vm.state.value.order)
            instrumentation.runOnMainSync { vm.cancelOrder(pending) }
            assertEquals(listOf("old-b"), queriedIds.toList())
            assertEquals(current, vm.state.value.order)
            // Starting another business still sees the original unresolved intent.
            val outlet = WaterOutlet("fixture-outlet", "常温水")
            val device = WaterDevice("fixture-water", "本地饮水机", "LOCAL-IMEI", "shop", "org", listOf(outlet), true)
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val flow = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>
                flow.value = flow.value.copy(waterDevice = device, waterOutlet = outlet)
                vm.startWater()
            }
            assertEquals("请先处理未完成的订单", vm.state.value.message)
            assertEquals(listOf("old-b"), queriedIds.toList())

            // Once A is confirmed closed, an explicit action may promote C; its own ID is queried.
            instrumentation.runOnMainSync { vm.refreshOrder(current) }
            await { !vm.state.value.busy && vm.state.value.order?.phase == OrderPhase.CLOSED }
            instrumentation.runOnMainSync { vm.resumePayment(pending) }
            await { !vm.state.value.busy && vm.state.value.order?.orderId == pending.orderId && vm.state.value.order?.phase == OrderPhase.CLOSED }
            assertEquals(listOf("old-b", "current-a", "pending-c"), queriedIds.toList())
            assertEquals(pending.orderId, SessionVault(context).order()?.orderId)
            assertEquals(OrderPhase.REFUNDED, vm.state.value.orderHistory.single { it.orderId == old.orderId }.phase)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
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

    private fun order(owner: String, id: String, time: Long, phase: OrderPhase) = LocalOrder(
        owner, "测试设备 $id", "测试模式", "1.00", PayMethod.ALIPAY, phase,
        createdAt = time, orderId = id, orderNo = "number-$id"
    )
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for the local order fixture")
    }
}
