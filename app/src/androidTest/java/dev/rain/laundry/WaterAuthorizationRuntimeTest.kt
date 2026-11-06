package dev.rain.laundry

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import dev.rain.laundry.core.AgreementPolicy
import dev.rain.laundry.core.PayMethod
import dev.rain.laundry.core.WaterAlipayAuthorization
import dev.rain.laundry.core.WaterDevice
import dev.rain.laundry.core.WaterOutlet
import dev.rain.laundry.core.WaterPayRoute
import dev.rain.laundry.core.WaterPhase
import dev.rain.laundry.core.WaterSession
import dev.rain.laundry.core.WaterStatus
import dev.rain.laundry.data.ConsentStore
import dev.rain.laundry.data.LaundryApi
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.payments.WaterAlipayAuthorizationActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run only on the isolated, account-free emulator. Every API request ends in an application interceptor. */
@RunWith(AndroidJUnit4::class)
class WaterAuthorizationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val outlet = WaterOutlet("auth-water-sku", "常温水", "0.3 元/升")
    private val device = WaterDevice("auth-water-device", "本地授权测试饮水机", "AUTH-WATER-IMEI",
        "auth-shop", "3100001", listOf(outlet), true, tradeMain = 1)
    private val payment = WaterPayRoute("15", PayMethod.ALIPAY)
    private val authorizationUrl = "https://openapi.alipay.com/gateway.do?app_id=local-fixture&sign=fixture"
    private val deviceReadPaths = listOf("/goods/normal/details", "/goods/normal/skus", "/popup/shop")
    private val recheckPaths = deviceReadPaths + "/alipay/isSign"

    @Test fun returningFromAuthorizationOnlyRefreshesAndRechecksBeforeAnotherExplicitConfirmation() = withFixture { fixture ->
        // Optional opening prompts may survive a successful signature.
        fixture.signingEnvelope.set("""{"code":0,"data":[{"popupType":4,"isForceUse":0}]}""")
        assertTrue(fixture.vm.canLaunchWaterAuthorization(fixture.launch))
        fixture.returnFromBridge("本地模拟SDK返回9000")
        fixture.awaitFinished()
        assertEquals(recheckPaths, fixture.paths())
        assertEquals("1", fixture.calls.first { it.path == "/popup/shop" }.fields["tradeMain"])
        assertTrue(fixture.vm.state.value.waterAuthorizationMessage.orEmpty().contains("平台未再要求签约"))
        assertTrue(fixture.vm.state.value.waterAuthorizationMessage.orEmpty().contains("重新确认用水"))
        fixture.assertNoActivationOrSecondHandoff()
    }

    @Test fun unfinishedSigningOrFailedRechecksNeverBecomeAuthorizationSuccessOrAnotherHandoff() {
        for (mode in listOf("still-required", "business-failure", "network-failure")) {
            withFixture { fixture ->
                when (mode) {
                    "still-required" -> fixture.signingEnvelope.set("""{"code":0,"data":[{"popupType":1,"isForceUse":1}]}""")
                    "business-failure" -> fixture.signingEnvelope.set("""{"code":4608,"msg":"本地模拟签约查询失败","data":null}""")
                    "network-failure" -> fixture.failSigningRequest = true
                }
                fixture.returnFromBridge("本地模拟SDK返回9000")
                fixture.awaitFinished()
                assertEquals(mode, if (mode == "network-failure") List(3) { deviceReadPaths }.flatten() else deviceReadPaths, fixture.paths())
                val message = fixture.vm.state.value.waterAuthorizationMessage.orEmpty()
                assertTrue(mode, if (mode == "still-required") message.contains("仍要求") else message.contains("尚未确认"))
                assertFalse(mode, message.contains("平台未再要求签约"))
                fixture.assertNoActivationOrSecondHandoff()
            }
        }
    }

    @Test fun firstReturnNetworkFailureRetriesOnlyReadsAndAcceptsTheLaterShopResult() = withFixture { fixture ->
        fixture.transientFailures.set(1)
        fixture.returnFromBridge()
        fixture.awaitFinished()
        assertEquals(deviceReadPaths + recheckPaths, fixture.paths())
        assertTrue(fixture.vm.state.value.waterAuthorizationMessage.orEmpty().contains("平台未再要求签约"))
        fixture.assertNoActivationOrSecondHandoff()
    }

    @Test fun anOptionalPromptAndUnsyncedAccountStatusDoNotReopenSigningOrClaimTheSignatureFailed() = withFixture { fixture ->
        fixture.signingEnvelope.set("""{"code":0,"data":[{"popupType":4,"isForceUse":0}]}""")
        fixture.accountEnvelope.set("""{"code":0,"data":false}""")
        fixture.returnFromBridge()
        fixture.awaitFinished()
        assertEquals(recheckPaths, fixture.paths())
        val message = fixture.vm.state.value.waterAuthorizationMessage.orEmpty()
        assertTrue(message.contains("可再次确认用水"))
        assertFalse(message.contains("尚未完成"))
        fixture.assertNoActivationOrSecondHandoff()
    }

    @Test fun manualRefreshReplacesTheOldUnfinishedMessageAfterConnectivityRecovers() = withFixture { fixture ->
        fixture.failSigningRequest = true
        fixture.returnFromBridge()
        fixture.awaitFinished()
        assertTrue(fixture.vm.state.value.waterAuthorizationMessage.orEmpty().contains("尚未确认"))
        val before = fixture.calls.size
        fixture.failSigningRequest = false
        instrumentation.runOnMainSync { fixture.vm.refreshWater() }
        fixture.awaitFinished()
        assertEquals(listOf("/payChannelRoute/afterPay", "/goods/water/sync") + recheckPaths,
            fixture.paths().drop(before))
        assertTrue(fixture.vm.state.value.waterAuthorizationMessage.orEmpty().contains("平台未再要求签约"))
        fixture.assertNoActivationOrSecondHandoff()
    }

    @Test fun shopSigningMerchantCanDifferFromTheBoundDeviceDetailWithoutRepeatingHandoff() =
        withFixture(authorizationMerchant = 2) { fixture ->
            assertTrue(fixture.vm.canLaunchWaterAuthorization(fixture.launch))
            fixture.returnFromBridge()
            fixture.awaitFinished()
            assertEquals(recheckPaths, fixture.paths())
            assertEquals("1", fixture.calls.first { it.path == "/popup/shop" }.fields["tradeMain"])
            assertTrue(fixture.vm.state.value.waterAuthorizationMessage.orEmpty().contains("平台未再要求签约"))
            fixture.assertNoActivationOrSecondHandoff()
        }

    @Test fun staleOwnerDeviceMerchantAndRequestResultsCannotApplyToTheNewContext() {
        for (change in listOf("owner", "device", "merchant", "route")) {
            withFixture { fixture ->
                instrumentation.runOnMainSync {
                    val current = fixture.state.value
                    when (change) {
                        "owner" -> fixture.vault.login("local-water-auth-other-owner", "fixture-token")
                        "device" -> fixture.state.value = current.copy(waterDevice = device.copy(id = "another-device"))
                        "merchant" -> fixture.state.value = current.copy(waterDevice = device.copy(tradeMain = 2))
                        "route" -> fixture.state.value = current.copy(waterPayment = WaterPayRoute.BALANCE)
                    }
                    fixture.state.value = fixture.state.value.copy(waterAuthorizationMessage = "新上下文保留此提示")
                }
                fixture.returnFromBridge()
                fixture.awaitFinished()
                assertTrue(change, fixture.calls.isEmpty())
                assertEquals(change, "新上下文保留此提示", fixture.vm.state.value.waterAuthorizationMessage)
                fixture.assertNoActivationOrSecondHandoff()
            }
        }
        withFixture { fixture ->
            fixture.freshDevice.set(device.copy(tradeMain = 2))
            fixture.returnFromBridge()
            fixture.awaitFinished()
            assertEquals(listOf("/goods/normal/details", "/goods/normal/skus"), fixture.paths())
            assertTrue(fixture.vm.state.value.waterAuthorizationMessage.orEmpty().contains("商户信息已变化"))
            fixture.assertNoActivationOrSecondHandoff()
        }
        withFixture { fixture ->
            fixture.pauseSigningRequest = true
            fixture.returnFromBridge()
            assertTrue("The local signing response should be waiting", fixture.signingEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                fixture.vault.login("local-water-auth-owner-changed-in-flight", "other-account-fixture-token")
                fixture.state.value = fixture.state.value.copy(waterAuthorizationMessage = "新账户的提示不能被旧响应覆盖")
            }
            fixture.releaseSigning.countDown()
            fixture.awaitFinished()
            assertEquals(deviceReadPaths, fixture.paths())
            assertEquals("新账户的提示不能被旧响应覆盖", fixture.vm.state.value.waterAuthorizationMessage)
            fixture.assertNoActivationOrSecondHandoff()
        }
        withFixture { fixture ->
            instrumentation.runOnMainSync { fixture.vm.waterAuthorizationReturned("a-different-request") }
            instrumentation.waitForIdleSync()
            assertTrue(fixture.calls.isEmpty())
            assertTrue("An unrelated return must not clear the current request", fixture.vm.state.value.waterAuthorizationPending)
            assertTrue(fixture.vm.canLaunchWaterAuthorization(fixture.launch))
        }
    }

    @Test fun bridgeWithoutAlipayReturnsTheRequestIdentityWithoutOpeningAnSdkAuthorization() = withLocalPreferences {
        @Suppress("DEPRECATION")
        val alipayInstalled = try {
            context.packageManager.getPackageInfo("com.eg.android.AlipayGphone", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        assumeFalse("Never invoke the signing SDK on a device with Alipay installed", alipayInstalled)
        assertTrue(ConsentStore(context).accept(AgreementPolicy.CONFIRMATION))
        val launch = WaterAuthorizationLaunch(UUID.randomUUID().toString(), "local-bridge-owner", device.id, payment.wire,
            WaterAlipayAuthorization(1, authorizationUrl), device.imei, device.orgId)
        ActivityScenario.launchActivityForResult<WaterAlipayAuthorizationActivity>(
            WaterAlipayAuthorizationActivity.intent(context, launch)
        ).use { scenario ->
            val result = scenario.result
            assertEquals(Activity.RESULT_CANCELED, result.resultCode)
            assertEquals(launch.id, result.resultData.getStringExtra(WaterAlipayAuthorizationActivity.EXTRA_REQUEST_ID))
            assertTrue(result.resultData.getStringExtra(WaterAlipayAuthorizationActivity.EXTRA_MESSAGE).orEmpty().contains("未安装支付宝"))
        }
    }

    private data class RecordedCall(val path: String, val fields: Map<String, String>)

    private inner class Fixture(authorizationMerchant: Int = 1) {
        val vault = SessionVault(context).apply { login("local-water-authorization-runtime", "fixture-token") }
        val owner = vault.owner()
        val launch = WaterAuthorizationLaunch(UUID.randomUUID().toString(), owner, device.id, payment.wire,
            WaterAlipayAuthorization(authorizationMerchant, authorizationUrl), device.imei, device.orgId,
            device.tradeMain, device.shopId)
        val calls = CopyOnWriteArrayList<RecordedCall>()
        val signingEnvelope = AtomicReference("""{"code":0,"data":[]}""")
        val accountEnvelope = AtomicReference("""{"code":0,"data":true}""")
        val freshDevice = AtomicReference(device)
        val signingEntered = CountDownLatch(1)
        val releaseSigning = CountDownLatch(1)
        @Volatile var pauseSigningRequest = false
        @Volatile var failSigningRequest = false
        val transientFailures = java.util.concurrent.atomic.AtomicInteger(0)
        private val store = ViewModelStore()
        lateinit var vm: LaundryViewModel
        lateinit var state: MutableStateFlow<UiState>
        private lateinit var authorizationEvents: Channel<WaterAuthorizationLaunch>
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request()
            val form = request.body as? FormBody
            val fields = form?.let { body -> (0 until body.size).associate { body.name(it) to body.value(it) } }.orEmpty()
            val path = request.url.encodedPath
            calls += RecordedCall(path, fields)
            val body = when (path) {
                "/goods/normal/details" -> detailResponse(freshDevice.get())
                "/alipay/isSign" -> accountEnvelope.get()
                "/payChannelRoute/afterPay" -> """{"code":0,"data":[{"method":15,"isThird":0}]}"""
                "/goods/water/sync" -> """{"code":0,"data":{"status":6,"workStatus":1}}"""
                "/goods/normal/skus" -> """{"code":0,"data":[{"skuId":"auth-water-sku","name":"常温水","extAttr":{"level":[{"price":0.3}]}}]}"""
                "/popup/shop" -> {
                    signingEntered.countDown()
                    if (pauseSigningRequest && !releaseSigning.await(5, TimeUnit.SECONDS)) throw IOException("Local response gate timed out")
                    if (failSigningRequest || transientFailures.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw IOException("Local simulated disconnect")
                    signingEnvelope.get()
                }
                else -> """{"code":4900,"msg":"本地测试已阻止非预期接口"}"""
            }
            // Never call chain.proceed: this test cannot reach a socket or the real service.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Local authorization fixture")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()

        init {
            instrumentation.runOnMainSync {
                val application = context.applicationContext as Application
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[LaundryViewModel::class.java]
                val api = LaundryViewModel::class.java.getDeclaredField("api").apply { isAccessible = true }.get(vm) as LaundryApi
                LaundryApi::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, client)
                @Suppress("UNCHECKED_CAST")
                val mutable = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>
                state = mutable
                @Suppress("UNCHECKED_CAST")
                val events = LaundryViewModel::class.java.getDeclaredField("waterAuthorizationChannel").apply { isAccessible = true }
                    .get(vm) as Channel<WaterAuthorizationLaunch>
                authorizationEvents = events
                state.value = UiState(loggedIn = true, phoneLabel = "本地合成账户", page = Page.WATER,
                    waterDevice = device, waterOutlet = outlet, waterStatus = WaterStatus(0, 1),
                    waterPayments = listOf(payment), waterPayment = payment,
                    waterSession = WaterSession(owner, device, outlet, WaterPhase.REJECTED, payment = payment),
                    waterAuthorizationPending = true)
                LaundryViewModel::class.java.getDeclaredField("pendingWaterAuthorization").apply { isAccessible = true }.set(vm, launch)
            }
        }

        fun returnFromBridge(message: String? = null) {
            instrumentation.runOnMainSync { vm.waterAuthorizationReturned(launch.id, message) }
        }

        fun awaitFinished() = await { !vm.state.value.waterAuthorizationPending && !vm.state.value.busy }
        fun paths() = calls.map { it.path }

        fun assertNoActivationOrSecondHandoff() {
            assertFalse(paths().contains("/goods/water/unlock"))
            assertFalse(paths().contains("/payChannelRoute/addUserAfterPayChannel"))
            assertTrue("A bridge return must not enqueue another signing launch", authorizationEvents.tryReceive().isFailure)
            assertFalse(vm.state.value.waterAuthorizationPending)
            assertFalse(vm.canLaunchWaterAuthorization(launch))
            assertEquals(WaterPhase.REJECTED, vm.state.value.waterSession?.phase)
            assertEquals("", vm.state.value.waterSession?.orderNo)
            assertNull(vm.state.value.order)
        }

        fun close() {
            releaseSigning.countDown()
            instrumentation.runOnMainSync { store.clear() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun detailResponse(value: WaterDevice): String = JsonObject().apply {
        addProperty("code", 0)
        add("data", JsonObject().apply {
            addProperty("goodsId", value.id); addProperty("name", value.name); addProperty("imei", value.imei)
            addProperty("shopId", value.shopId); addProperty("orgId", value.orgId); addProperty("categoryCode", "04")
            addProperty("payment", "after"); addProperty("isBluetooth", 0); addProperty("supportAppUserFinish", true)
            value.tradeMain?.let { addProperty("tradeMain", it) }
            add("extAttr", JsonObject().apply { addProperty("flowmeter", 1) })
        })
    }.toString()

    private fun withFixture(authorizationMerchant: Int = 1, test: (Fixture) -> Unit) = withLocalPreferences {
        val fixture = Fixture(authorizationMerchant)
        try { test(fixture) } finally { fixture.close() }
    }

    private fun withLocalPreferences(test: () -> Unit) {
        assumeTrue("Use the isolated emulator, never an account-bearing phone", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val vault = SessionVault(context)
        assumeTrue("The emulator must not contain a real signed-in account", vault.token().isEmpty() || vault.token() == "fixture-token")
        val names = listOf("laundry_vault", "usage_agreement", "appearance")
        val previous = names.associateWith { name -> context.getSharedPreferences(name, 0).all.toMap() }
        assertTrue(context.getSharedPreferences("laundry_vault", 0).edit().clear().commit())
        try { test() }
        finally { previous.forEach { (name, snapshot) -> restore(context.getSharedPreferences(name, 0), snapshot) } }
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

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail("Timed out waiting for the local authorization recheck")
    }
}
