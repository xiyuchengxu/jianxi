package dev.rain.laundry

import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.*
import dev.rain.laundry.data.ConsentStore
import dev.rain.laundry.data.LaundryApi
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.notifications.LaundryMonitorEngine
import dev.rain.laundry.notifications.LaundryNotificationScheduler
import dev.rain.laundry.notifications.LaundryReminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Uses synthetic emulator accounts. Every HTTP request ends at the interceptor. */
@RunWith(AndroidJUnit4::class)
class LaundryProgressRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Test fun serverRunningThenCompletedWithoutFinishTimePostsOnlyOneNotification() = withFixture { fixture ->
        assertTrue("Grant POST_NOTIFICATIONS on the dedicated test emulator", LaundryReminders.notificationsEnabled(context))
        fixture.seed()
        val first = LaundryMonitorEngine.check(context, fixture.owner, fixture.api)
        assertFalse(first.retry)
        assertNotNull(first.nextCheckAtMs)
        val running = requireNotNull(fixture.vault.order())
        assertEquals(LaundryRunState.RUNNING, running.laundryProgress?.runState)
        assertEquals(10, running.laundryProgress?.totalMinutes)
        assertTrue(requireNotNull(running.laundryProgress?.remainingMillis(System.currentTimeMillis())) in 1..600_000L)
        assertEquals(1, fixture.vault.laundryReminders().size)
        assertFalse(fixture.vault.laundryReminders().single().notificationClaimed)

        fixture.status.set(3) // Intentionally no finishTime: authoritative ordinary-laundry status is sufficient.
        val complete = LaundryMonitorEngine.check(context, fixture.owner, fixture.api)
        assertFalse(complete.retry)
        assertNull(complete.nextCheckAtMs)
        assertEquals(OrderPhase.COMPLETED, fixture.vault.order()?.phase)
        assertEquals(LaundryRunState.FINISHED, fixture.vault.order()?.laundryProgress?.runState)
        assertTrue(fixture.vault.laundryReminders().single().notificationClaimed)
        val key = OrderHistory.key(fixture.order)
        assertEquals(1, notifications.activeNotifications.count { it.tag == key })
        LaundryMonitorEngine.check(context, fixture.owner, fixture.api)
        assertEquals(1, notifications.activeNotifications.count { it.tag == key })
        assertEquals(listOf("/order/detail", "/order/detail"), fixture.calls.toList())
    }

    @Test fun anExpiredEstimateStillRunningDoesNotNotifyAndSchedulesAnotherRead() = withFixture { fixture ->
        fixture.endAtMs.set(System.currentTimeMillis() - 60_000)
        fixture.seed()
        val before = System.currentTimeMillis()
        val result = LaundryMonitorEngine.check(context, fixture.owner, fixture.api)
        val order = requireNotNull(fixture.vault.order())
        assertEquals(OrderPhase.PAID, order.phase)
        assertEquals(LaundryRunState.RUNNING, order.laundryProgress?.runState)
        assertEquals(0L, order.laundryProgress?.remainingMillis(System.currentTimeMillis()))
        assertFalse(requireNotNull(order.laundryProgress).completionConfirmed)
        assertTrue(requireNotNull(result.nextCheckAtMs) > before)
        assertFalse(fixture.vault.laundryReminders().single().notificationClaimed)
        assertNull(fixture.vault.laundryReminders().single().completedAtMs)
        assertTrue(notifications.activeNotifications.none { it.tag == OrderHistory.key(fixture.order) })
        assertEquals(listOf("/order/detail"), fixture.calls.toList())
    }

    @Test fun aForegroundOperationInvalidatesAnOlderReadButKeepsTheMonitorScheduled() = withFixture { fixture ->
        fixture.seed()
        val gate = fixture.gateNextReply()
        coroutineScope {
            val query = async(Dispatchers.Default) { LaundryMonitorEngine.check(context, fixture.owner, fixture.api) }
            assertTrue("The old read should reach its local response gate", gate.first.await(10, TimeUnit.SECONDS))
            LaundryMonitorEngine.beginForeground()
            try {
                gate.second.countDown()
                val result = query.await()
                assertNotNull("Temporary foreground work must not permanently end the monitor", result.nextCheckAtMs)
                assertEquals(fixture.order, fixture.vault.order())
                assertTrue(fixture.vault.laundryReminders().isEmpty())
            } finally { LaundryMonitorEngine.endForeground(); gate.second.countDown() }
        }
        assertEquals(listOf("/order/detail"), fixture.calls.toList())
    }

    @Test fun sameOwnerReloginDiscardsThePreviousTokensInFlightReply() = withFixture { fixture ->
        fixture.seed()
        val gate = fixture.gateNextReply()
        coroutineScope {
            val query = async(Dispatchers.Default) { LaundryMonitorEngine.check(context, fixture.owner, fixture.api) }
            assertTrue(gate.first.await(10, TimeUnit.SECONDS))
            fixture.vault.login(fixture.phone, "second-synthetic-token")
            gate.second.countDown()
            val result = query.await()
            assertNull("A different login session should end the old worker chain", result.nextCheckAtMs)
            assertEquals(fixture.order, fixture.vault.order())
            assertTrue(fixture.vault.laundryReminders().isEmpty())
        }
        assertEquals(listOf("/order/detail"), fixture.calls.toList())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun progressRendersAndTheRealNotificationOpensItsOrderWithoutReplacingThePointer() = withFixture { fixture ->
        assertTrue("Grant POST_NOTIFICATIONS on the dedicated test emulator", LaundryReminders.notificationsEnabled(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: LaundryViewModel
            lateinit var launchedActivity: MainActivity
            lateinit var scenarioIntent: Intent
            scenario.onActivity { activity ->
                launchedActivity = activity
                scenarioIntent = activity.intent
                vm = ViewModelProvider(activity)[LaundryViewModel::class.java]
                val api = LaundryViewModel::class.java.getDeclaredField("api").apply { isAccessible = true }.get(vm) as LaundryApi
                LaundryApi::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, fixture.client)
                fixture.seed()
                val state = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<UiState>
                state.value = UiState(loggedIn = true, phoneLabel = "本地进度模拟账户", page = Page.ORDERS,
                    order = fixture.order, orderHistory = listOf(fixture.order))
                vm.refreshLaundryProgress()
            }
            await { vm.state.value.order?.laundryProgress?.runState == LaundryRunState.RUNNING }
            await { findText("预计剩余") != null }
            assertNull(findText("洗衣结束通知已开启"))
            assertNull(findText("当前账号在本机保留最近"))
            assertFalse("A progress read must not disable the foreground UI", vm.state.value.busy)
            capture("laundry-progress-running")
            click("设置导航")
            click("通知设置")
            await { findText("洗衣结束通知已开启") != null }
            assertNotNull(findText("当前账号在本机保留最近"))
            assertNotNull(findText("洗衣进度自动同步"))
            capture("laundry-notification-settings")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { findText("通知设置") != null }
            click("订单导航")
            await { findText("预计剩余") != null }
            fixture.status.set(3)
            await {
                instrumentation.runOnMainSync { vm.refreshLaundryProgress() }
                vm.state.value.order?.phase == OrderPhase.COMPLETED
            }
            await { findText("洗衣已结束") != null }
            val key = OrderHistory.key(fixture.order)
            var posted: android.service.notification.StatusBarNotification? = null
            await { posted = notifications.activeNotifications.firstOrNull { it.tag == key }; posted != null }
            instrumentation.runOnMainSync { vm.navigate(Page.HOME) }
            val pointerId = vm.state.value.order?.orderId
            requireNotNull(posted).notification.contentIntent.send()
            await { vm.state.value.page == Page.ORDERS && vm.state.value.orderFocusKey == key }
            assertEquals(pointerId, vm.state.value.order?.orderId)
            assertFalse(vm.state.value.busy)
            capture("laundry-progress-completed")
            assertTrue(fixture.calls.isNotEmpty())
            assertTrue(fixture.calls.all { it == "/order/detail" })
            // onNewIntent legitimately replaces the Activity intent. Restore the scenario's
            // launch marker before its cleanup monitor matches the destruction callback.
            instrumentation.runOnMainSync { launchedActivity.intent = scenarioIntent }
        }
    }

    private class Fixture(val vault: SessionVault) {
        val phone = "laundry-progress-synthetic-owner"
        val owner = WireProtocol.sha256(phone)
        val status = AtomicInteger(2)
        val endAtMs = AtomicLong(System.currentTimeMillis() + 600_000)
        val calls = CopyOnWriteArrayList<String>()
        private val gate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>(null)
        val order = LocalOrder(owner, "本地测试洗衣机", "标准洗", "4.00", PayMethod.ALIPAY,
            OrderPhase.PAID, createdAt = System.currentTimeMillis() - 30_000,
            orderId = "local-progress-order", orderNo = "local-progress-number", syncRequested = true)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            check(path == "/order/detail") { "Blocked non-read operation: $path" }
            val form = request.body as FormBody
            val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
            check(fields["orderId"] == order.orderId) { "Wrong synthetic order target" }
            calls += path
            gate.getAndSet(null)?.let { pair ->
                pair.first.countDown()
                check(pair.second.await(15, TimeUnit.SECONDS)) { "Synthetic response gate timed out" }
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            val end = time.format(Date(endAtMs.get()))
            val begin = time.format(Date(endAtMs.get() - 600_000))
            val body = """{"code":0,"data":{"id":"${order.orderId}","orderNo":"${order.orderNo}","orderStatus":${status.get()},"orderType":0,"machineId":"local-progress-machine","imei":"LOCAL-PROGRESS-IMEI","markMinutes":"10","beginTime":"$begin","completeTime":"$end"}}"""
            // Never invoke chain.proceed; no request leaves this process.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        val api = LaundryApi(vault::token, "Synthetic", client)
        fun seed() { vault.login(phone, "first-synthetic-token"); vault.saveOrder(order) }
        fun gateNextReply(): Pair<CountDownLatch, CountDownLatch> =
            Pair(CountDownLatch(1), CountDownLatch(1)).also(gate::set)
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) {
        assumeTrue("Only the dedicated account-free emulator is allowed", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val vaultPrefs = context.getSharedPreferences("laundry_vault", 0)
        val appearance = context.getSharedPreferences("appearance", 0)
        val agreement = context.getSharedPreferences("usage_agreement", 0)
        val snapshots = listOf(vaultPrefs, appearance, agreement).associateWith { it.all.toMap() }
        LaundryNotificationScheduler.cancelAll(context)
        notifications.cancelAll()
        assertTrue(vaultPrefs.edit().clear().commit())
        assertTrue(appearance.edit().clear().putBoolean("splash_enabled", false).commit())
        assertTrue(agreement.edit().clear().commit())
        assertTrue(ConsentStore(context).accept(AgreementPolicy.CONFIRMATION))
        val fixture = Fixture(SessionVault(context))
        try { runBlocking { block(fixture) } }
        finally {
            LaundryNotificationScheduler.cancelAll(context)
            notifications.cancelAll()
            fixture.client.dispatcher.executorService.shutdown()
            fixture.client.connectionPool.evictAll()
            snapshots.forEach { (prefs, previous) -> restore(prefs, previous) }
        }
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
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(60)
        }
        fail("Timed out waiting for synthetic laundry progress")
    }
    private fun findText(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun click(text: String) {
        await {
            var node = findText(text)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "runtime-checks").apply { mkdirs() }
        try { File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
