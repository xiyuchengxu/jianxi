package dev.rain.laundry

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.*
import dev.rain.laundry.data.LaundryApi
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local, account-free emulator fixtures: every API request terminates before reaching a socket. */
@RunWith(AndroidJUnit4::class)
class WaterPresentationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val outlet = WaterOutlet("presentation-sku", "常温水", "0.3 元/升")
    private val device = WaterDevice("presentation-device", "本地测试饮水机", "PRESENTATION-IMEI", "shop", "org",
        listOf(outlet), true, tradeMain = 1)

    @Test fun closedRecordsNeverBecomeARememberedScanTargetOnLaunchOrReentry() = withFixture(WaterPhase.ENDED) { fixture ->
        assertNull(fixture.vm.state.value.waterDevice)
        assertEquals(WaterPhase.ENDED, fixture.vm.state.value.waterSession?.phase)
        instrumentation.runOnMainSync { fixture.vm.navigate(Page.WATER) }
        assertNull(fixture.vm.state.value.waterDevice)
        instrumentation.runOnMainSync { fixture.vm.openWaterSession() }
        assertNull(fixture.vm.state.value.waterDevice)
        instrumentation.runOnMainSync { fixture.vm.navigate(Page.HOME); fixture.vm.navigate(Page.WATER) }
        assertNull(fixture.vm.state.value.waterDevice)
        assertNull(fixture.vm.state.value.waterOutlet)
        assertEquals(WaterPhase.ENDED, fixture.vault.water()?.phase)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun activeTransactionCanBeExplicitlyRecoveredWithoutRememberingAnIdleDevice() = withFixture(WaterPhase.USING) { fixture ->
        assertNull(fixture.vm.state.value.waterDevice)
        assertTrue(fixture.vm.state.value.waterSession?.active == true)
        instrumentation.runOnMainSync { fixture.vm.openWaterSession() }
        assertEquals(device, fixture.vm.state.value.waterDevice)
        assertEquals(outlet, fixture.vm.state.value.waterOutlet)
        assertEquals(Page.WATER, fixture.vm.state.value.page)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun silentPollNeverDisablesControlsAndCannotOverwriteAForegroundStop() = withFixture(WaterPhase.USING) { fixture ->
        instrumentation.runOnMainSync {
            fixture.vm.openWaterSession()
            fixture.vm.refreshWater(silent = true)
        }
        assertTrue(fixture.firstReadEntered.await(5, TimeUnit.SECONDS))
        assertFalse("A background read must not disable the page", fixture.vm.state.value.busy)
        instrumentation.runOnMainSync { fixture.vm.endWater() }
        await { !fixture.vm.state.value.busy && fixture.vm.state.value.waterSession?.phase == WaterPhase.ENDED }
        fixture.releaseFirstRead.countDown()
        await { fixture.refreshJob()?.isCompleted == true }
        assertEquals(WaterPhase.ENDED, fixture.vm.state.value.waterSession?.phase)
        assertEquals(OrderPhase.COMPLETED, fixture.vm.state.value.order?.phase)
        assertEquals("0.15", fixture.vm.state.value.order?.amount)
        assertEquals(1, fixture.calls.count { it == "/goods/endWater" })
        assertFalse(fixture.calls.contains("/goods/water/unlock"))
    }

    private inner class Fixture(phase: WaterPhase) {
        val vault = SessionVault(context).apply { login("presentation-runtime-owner", "fixture-token") }
        val calls = CopyOnWriteArrayList<String>()
        val firstReadEntered = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        private val reads = AtomicInteger()
        private val store = ViewModelStore()
        @Volatile private var stopped = false
        lateinit var vm: LaundryViewModel
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            calls += path
            val data = when (path) {
                "/goods/water/sync" -> {
                    val wasStopped = stopped
                    if (reads.incrementAndGet() == 1) {
                        firstReadEntered.countDown()
                        check(releaseFirstRead.await(10, TimeUnit.SECONDS)) { "Local response gate timed out" }
                    }
                    if (wasStopped) """{"status":3,"workStatus":1,"identify":"presentation-usage","amount":"0.15"}"""
                    else """{"status":2,"workStatus":2,"identify":"presentation-usage","amount":"0.12"}"""
                }
                "/goods/endWater" -> { stopped = true; "true" }
                "/order/afterPay/creating" -> """{"orderId":"presentation-bill"}"""
                "/order/detail" -> """{"id":"presentation-bill","orderNo":"presentation-order","payType":2,"orderStatus":3,"payPrice":"0.15"}"""
                else -> throw AssertionError("Unexpected local operation: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture")
                .body("""{"code":0,"data":$data}""".toResponseBody("application/json".toMediaType())).build()
        }.build()

        init {
            val status = WaterStatus(if (phase == WaterPhase.ENDED) 3 else 2, if (phase == WaterPhase.ENDED) 1 else 2,
                "0.12", "presentation-usage")
            vault.saveWater(WaterSession(vault.owner(), device, outlet, phase, identify = "presentation-usage",
                lastStatus = status, observedRunning = true, payment = WaterPayRoute.BALANCE))
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[LaundryViewModel::class.java]
                val api = LaundryViewModel::class.java.getDeclaredField("api").apply { isAccessible = true }.get(vm) as LaundryApi
                LaundryApi::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, client)
            }
        }

        fun refreshJob() = LaundryViewModel::class.java.getDeclaredField("waterRefreshJob").apply { isAccessible = true }.get(vm) as? Job
        fun close() {
            releaseFirstRead.countDown()
            instrumentation.runOnMainSync { store.clear() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun withFixture(phase: WaterPhase, test: (Fixture) -> Unit) {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val vault = SessionVault(context)
        assumeTrue(vault.token().isEmpty() || vault.token() == "fixture-token")
        val preferences = context.getSharedPreferences("laundry_vault", 0)
        val previous = preferences.all.toMap()
        assertTrue(preferences.edit().clear().commit())
        var fixture: Fixture? = null
        try { fixture = Fixture(phase); test(fixture) }
        finally { fixture?.close(); restore(preferences, previous) }
    }

    private fun restore(preferences: SharedPreferences, previous: Map<String, *>) {
        val editor = preferences.edit().clear()
        previous.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
        } }
        assertTrue(editor.commit())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for the local presentation fixture")
    }
}
