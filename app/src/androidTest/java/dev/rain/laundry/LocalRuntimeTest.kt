package dev.rain.laundry

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.data.LaundryApi
import dev.rain.laundry.core.*
import dev.rain.laundry.ui.GlassLevel
import dev.rain.laundry.ui.ThemePreferences
import dev.rain.laundry.ui.BottomBarStyle
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Runs on a fresh local emulator. All LaundryApi requests are intercepted before networking. */
@RunWith(AndroidJUnit4::class)
class LocalRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val screenshots get() = File(instrumentation.targetContext.getExternalFilesDir(null), "runtime-checks").apply { mkdirs() }

    @Test fun glassControlsPersistAndFixedBarRetainsTheSelectedLevel() {
        val context = instrumentation.targetContext
        val preferences = ThemePreferences(context)
        val original = preferences.settings
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                acceptTermsIfNeeded()
                click("设置")
                click("主题设置")
                scrollToText("轻度")
                click("轻度")
                await { ThemePreferences(context).settings.glassLevel == GlassLevel.LIGHT }
                scrollToText("液态玻璃轻度交互预览")
                val preview = Rect().also(awaitText("液态玻璃轻度交互预览")::getBoundsInScreen)
                val downTime = SystemClock.uptimeMillis()
                injectTouch(downTime, MotionEvent.ACTION_DOWN, preview.centerX().toFloat(), preview.bottom - 36f)
                SystemClock.sleep(180)
                injectTouch(downTime, MotionEvent.ACTION_MOVE, preview.left + preview.width() * .2f, preview.bottom - 36f)
                injectTouch(downTime, MotionEvent.ACTION_UP, preview.left + preview.width() * .2f, preview.bottom - 36f)
                click("无")
                await { ThemePreferences(context).settings.glassLevel == GlassLevel.NONE }
                scrollToText("固定", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                click("固定")
                scrollToText("当前为固定底栏，不使用液态玻璃。所选等级会在切回悬浮底栏后生效。")
                assertEquals(GlassLevel.NONE, ThemePreferences(context).settings.glassLevel)
                scenario.recreate()
                awaitText("主题")
                assertEquals(GlassLevel.NONE, ThemePreferences(context).settings.glassLevel)
                scrollToText("完全")
                click("完全")
                await { ThemePreferences(context).settings.glassLevel == GlassLevel.FULL }
                scrollToText("悬浮", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                click("悬浮")
                assertEquals(GlassLevel.FULL, ThemePreferences(context).settings.glassLevel)
            }
        } finally {
            instrumentation.runOnMainSync { preferences.update(original) }
        }
    }

    @Test fun firstLaunchRequiresExactAcknowledgementAndRemembersIt() {
        val preferences = instrumentation.targetContext.getSharedPreferences("usage_agreement", 0)
        val previousRevision = preferences.getString("accepted_revision", null)
        val previousTime = preferences.getLong("accepted_at", 0L)
        assertTrue(preferences.edit().clear().commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("免责声明与用户守则")
                for (attempt in 0 until 40) {
                    if (find(automation.rootInActiveWindow) { it.isEditable } != null) break
                    awaitNode { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(100)
                }
                val editor = awaitNode { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "我同意")
                }))
                instrumentation.waitForIdleSync()
                assertNotNull(find(automation.rootInActiveWindow) { it.text?.toString() == "免责声明与用户守则" })
                assertNull(find(automation.rootInActiveWindow) { it.text?.toString() == "手机号登录" })
                assertNull(preferences.getString("accepted_revision", null))
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, AgreementPolicy.CONFIRMATION)
                }))
                click("确认并进入")
                awaitText("手机号登录")
                assertNotNull(preferences.getString("accepted_revision", null))
                scenario.recreate()
                awaitText("手机号登录")
                assertNull(find(automation.rootInActiveWindow) { it.text?.toString() == "免责声明与用户守则" })
            }
        } finally {
            preferences.edit().clear().apply {
                if (previousRevision != null) putString("accepted_revision", previousRevision).putLong("accepted_at", previousTime)
            }.commit()
        }
    }

    @Test fun loginAboutAndOfflineLicenseRenderAndReturn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            acceptTermsIfNeeded()
            awaitText("手机号登录")
            capture("01-login")
            click("关于简洗")
            awaitText("GitHub · 项目源码")
            awaitText("版本 ${BuildConfig.VERSION_NAME}")
            capture("02-about")
            click("打赏支持")
            awaitText("支付宝打赏收款码")
            capture("11-donation")
            val previousImages = savedDonationImages()
            click("保存收款码")
            awaitText("已保存到相册")
            val createdImages = savedDonationImages() - previousImages
            try {
                assertEquals(1, createdImages.size)
                val context = instrumentation.targetContext
                val original = context.resources.openRawResource(R.raw.donation_alipay).use { it.readBytes() }
                val saved = context.contentResolver.openInputStream(createdImages.single())!!.use { it.readBytes() }
                assertArrayEquals("Saved QR image must preserve the supplied bytes", original, saved)
                capture("12-donation-saved")
            } finally {
                createdImages.forEach { instrumentation.targetContext.contentResolver.delete(it, null, null) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText("GitHub · 项目源码")
            scrollToText("阅读完整协议")
            click("阅读完整协议")
            awaitText("Permission is hereby granted")
            capture("03-license")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText("阅读完整协议")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText("手机号登录")
        }
    }

    @Test fun pastedDeviceReachesCheckoutWithNullPromotionLists() {
        val appearance = ThemePreferences(instrumentation.targetContext)
        val original = appearance.settings
        try {
            instrumentation.runOnMainSync { appearance.update(original.copy(bottomBar = BottomBarStyle.FLOATING,
                glassLevel = GlassLevel.FULL, laundryEnabled = true, waterEnabled = true)) }
            withLocalSession { vm, calls ->
                awaitText("开始洗衣")
                capture("04-home")
                click("粘贴设备码")
                awaitText("二维码内容")
                val editor = awaitNode { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "https://example.invalid/skip?NQT=00000000-0000-4000-8000-000000000001")
                }))
                click("识别设备")
                awaitText("选择洗衣模式")
                awaitText("本地测试洗衣机")
                capture("05-device")
                click("确认模式，查看金额")
                awaitText("确认这一次洗衣")
                await { !vm.state.value.busy }
                assertEquals(Page.CHECKOUT, vm.state.value.page)
                assertEquals("4.00", vm.state.value.quote?.amount)
                assertEquals(2, vm.state.value.methods.size)
                assertNull(vm.state.value.order)
                capture("06-checkout")
                assertEquals(listOf("/goods/scan/v2", "/goods/normal/details", "/goods/normal/skus",
                    "/appoint/checkMachineCanUse", "/trade/preview/v4", "/payChannelRoute/prePay"), calls.toList())

                val viewport = awaitNode { it.isScrollable }
                val viewportBounds = Rect().also(viewport::getBoundsInScreen)
                val navigationBounds = Rect().also(awaitText("订单")::getBoundsInScreen)
                assertTrue("Glass needs the scroll scene behind it",
                    viewportBounds.bottom > navigationBounds.top)
                // Reveal the final payment action without invoking it or requesting location.
                viewport.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                instrumentation.waitForIdleSync()
                awaitText("确认支付 ¥4.00")
                val paymentBounds = Rect().also(awaitText("确认支付 ¥4.00")::getBoundsInScreen)
                assertTrue("Payment label must remain fully above the navigation bar",
                    paymentBounds.height() > 0 && paymentBounds.bottom <= navigationBounds.top)
                capture("06-checkout")

                // Exercise the real pointer path, including Android RuntimeShader glass rendering.
                val tab = awaitText("订单")
                val rect = Rect().also(tab::getBoundsInScreen)
                val downTime = SystemClock.uptimeMillis()
                injectTouch(downTime, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
                SystemClock.sleep(180)
                capture("07-glass-pressed")
                injectTouch(downTime, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
                awaitText("我的订单")
                capture("08-orders")
                assertNull(vm.state.value.order)
            }
        } finally { instrumentation.runOnMainSync { appearance.update(original) } }
    }

    @Test fun selectedAndMalformedPromotionsStopBeforeOrderCreation() {
        for ((promotions, expected, name) in listOf(
            Triple("""[{"promotionType":1,"isUsed":true,"options":null,"participateList":null}]""",
                "报价附加了未选择的优惠", "09-selected-promotion-blocked"),
            Triple("""[{"promotionType":7,"isUsed":false,"options":{},"participateList":null}]""",
                "options：对象", "10-malformed-promotion-blocked")
        )) withLocalSession(promotions) { vm, calls ->
            instrumentation.runOnMainSync { vm.scan("SN=LOCAL-TEST-DEVICE") }
            await { !vm.state.value.busy && vm.state.value.page == Page.DEVICE }
            instrumentation.runOnMainSync { vm.preview() }
            await { !vm.state.value.busy && vm.state.value.message?.contains(expected) == true }
            awaitText(expected)
            assertEquals(Page.DEVICE, vm.state.value.page)
            assertNull(vm.state.value.quote)
            assertNull(vm.state.value.order)
            assertFalse(calls.any { it == "/trade/create" || it.startsWith("/pay/") })
            capture(name)
        }
    }

    @Test fun expiredOrderIsRecognizedFromEveryOrderAction() {
        for ((index, action) in listOf("刷新订单状态", "继续付款", "取消未付款订单").withIndex()) {
            withLocalSession(detailStatuses = listOf(1)) { vm, calls ->
                setLocalOrder(vm)
                awaitText("订单待付款")
                scrollToText(action)
                click(action)
                if (action == "取消未付款订单") { awaitText("取消这笔订单？"); click("确认取消") }
                await { !vm.state.value.busy && vm.state.value.order?.phase == OrderPhase.CLOSED }
                awaitText("订单已关闭")
                assertEquals(listOf("/order/detail"), calls.toList())
                assertTrue(vm.state.value.order!!.phase.finished)
                await { find(automation.rootInActiveWindow) { it.text?.contains("继续付款") == true } == null }
                capture("${13 + index}-closed-order")
            }
        }
    }

    @Test fun cancellationRace3122RefreshesTheClosedOrder() = withLocalSession(
        detailStatuses = listOf(0, 1), cancelRejected = true
    ) { vm, calls ->
        setLocalOrder(vm)
        scrollToText("取消未付款订单")
        click("取消未付款订单")
        awaitText("取消这笔订单？")
        click("确认取消")
        await { !vm.state.value.busy && vm.state.value.order?.phase == OrderPhase.CLOSED }
        awaitText("订单已关闭")
        assertEquals(listOf("/order/detail", "/order/pay_status", "/order/cancel", "/order/detail"), calls.toList())
        capture("16-closed-after-3122")
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun defaultAlipayProductReachesThePaymentHandoffWithoutCreatingAnotherOrder() = withLocalSession(
        allowPrepay = true
    ) { vm, calls ->
        setLocalOrder(vm)
        val channelField = LaundryViewModel::class.java.getDeclaredField("launchChannel").apply { isAccessible = true }
        val originalChannel = channelField.get(vm) as Channel<PaymentLaunch>
        val capturedChannel = Channel<PaymentLaunch>(Channel.BUFFERED)
        // MainActivity continues to collect its original flow. Capture the handoff before the real SDK.
        instrumentation.runOnMainSync { channelField.set(vm, capturedChannel) }
        try {
            scrollToText("继续付款")
            click("继续付款")
            await { !vm.state.value.busy && vm.state.value.order?.phase == OrderPhase.PAYMENT_PENDING }
            var launch: PaymentLaunch? = null
            await { launch = capturedChannel.tryReceive().getOrNull(); launch != null }
            assertEquals("local-trade-no", launch!!.orderNo)
            assertEquals(localAlipayPayload(), launch!!.payload)
            assertEquals("local-order-id", vm.state.value.order?.orderId)
            assertEquals(listOf("/order/detail", "/pay/unify/prePay"), calls.toList())
            capture("17-payment-parameters-accepted")
        } finally {
            instrumentation.runOnMainSync { channelField.set(vm, originalChannel) }
            capturedChannel.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun waterTabScansAndConfirmsRatesInBothBottomBarStyles() {
        val appearance = ThemePreferences(instrumentation.targetContext)
        val originalTheme = appearance.settings
        try {
            for (style in BottomBarStyle.entries) {
                instrumentation.runOnMainSync { appearance.update(originalTheme.copy(bottomBar = style, laundryEnabled = true, waterEnabled = true)) }
                withLocalSession(waterDevice = true) { vm, calls ->
                    click("饮水")
                    awaitText("开始饮水")
                    for (tab in listOf("洗衣", "饮水", "订单", "设置")) awaitText(tab)
                    capture("water-01-home-${style.name.lowercase()}")
                    click("粘贴饮水机设备码")
                    assertTrue(awaitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "IMEI=LOCAL-WATER-IMEI")
                    }))
                    click("识别设备")
                    awaitText("本地测试饮水机")
                    awaitText("0.3 元/升")
                    await { !vm.state.value.busy }
                    assertEquals(Page.WATER, vm.state.value.page)
                    capture("water-02-device-${style.name.lowercase()}")
                    scrollToText("开始用水")
                    click("开始用水")
                    awaitText("确认开始用水？")
                    capture("water-03-confirm-${style.name.lowercase()}")
                    click("取消")
                    assertFalse(calls.contains("/goods/water/unlock"))
                    assertFalse(calls.contains("/trade/create"))
                    click("洗衣")
                    awaitText("开始洗衣")
                }
            }
        } finally { instrumentation.runOnMainSync { appearance.update(originalTheme) } }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun alipayOnlyWaterMachineCanConfirmUseAfterHistoricalFailure() =
        withLocalSession(waterDevice = true, waterAlipay = true, waterPriorFailure = true) { vm, calls ->
            click("饮水")
            awaitText("开始饮水")
            click("粘贴饮水机设备码")
            assertTrue(awaitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "https://h5.qiekj.com/skip?SN=867000000000001")
            }))
            click("识别设备")
            await { vm.state.value.waterPaymentAllowed && !vm.state.value.busy }
            assertEquals("15", vm.state.value.waterPayment?.wire)
            assertEquals(PayMethod.ALIPAY, vm.state.value.waterPayment?.method)
            assertTrue(vm.state.value.waterPayments.none { it.method == PayMethod.BALANCE })
            assertTrue(vm.state.value.waterStatus?.available == true)
            awaitText("出水口空闲，请重新确认用水")
            scrollToText("开始用水")
            capture("water-alipay-only-device")
            click("开始用水")
            awaitText("确认开始用水？")
            awaitText("本次使用支付宝先享后付（免密）")
            capture("water-alipay-confirm")
            click("取消")
            assertFalse(calls.contains("/payChannelRoute/addUserAfterPayChannel"))
            assertFalse(calls.contains("/goods/water/unlock"))
            assertFalse(calls.contains("/pay/unify/pay"))
        }

    @Test fun ownWaterSessionStopsAndSettlesWithoutAnotherActivation() = withLocalSession(waterDevice = true, waterRunning = true) { vm, calls ->
        val outlet = WaterOutlet("local-water-sku", "常温水", "0.3 元/升")
        val water = WaterDevice("local-water-device", "本地测试饮水机", "LOCAL-WATER-IMEI", "water-shop", "3100001", listOf(outlet), true)
        instrumentation.runOnMainSync {
            val mutable = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>
            val status = WaterStatus(2, 2, "0.12", "local-water-usage")
            mutable.value = mutable.value.copy(page = Page.WATER, waterDevice = water, waterOutlet = outlet, waterStatus = status,
                waterSession = WaterSession("local-runtime-owner", water, outlet, WaterPhase.USING, lastStatus = status))
        }
        awaitText("正在用水")
        scrollToText("结束用水")
        click("结束用水")
        awaitText("结束本次用水？")
        click("确认结束用水")
        await { vm.state.value.waterSession?.phase == WaterPhase.ENDED && !vm.state.value.busy }
        assertEquals("0.15", vm.state.value.order?.amount)
        assertEquals(OrderPhase.COMPLETED, vm.state.value.order?.phase)
        assertEquals(true, vm.state.value.order?.isWater)
        assertEquals(1, calls.count { it == "/goods/endWater" })
        assertFalse(calls.contains("/goods/water/unlock"))
        assertFalse(calls.contains("/order/sync"))
        scrollToText("查看饮水账单")
        click("查看饮水账单")
        awaitText("我的订单")
        scrollToText("饮水账单已完成")
        capture("water-05-settled")
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun orderGroupsSeparateLaundryFromWaterAndCanRecoverTheActiveSession() = withLocalSession(waterDevice = true, waterRunning = true) { vm, calls ->
        val outlet = WaterOutlet("local-water-sku", "常温水", "0.3 元/升")
        val water = WaterDevice("local-water-device", "分组测试饮水机", "LOCAL-WATER-IMEI", "water-shop", "3100001", listOf(outlet), true)
        instrumentation.runOnMainSync {
            val mutable = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>
            val status = WaterStatus(2, 2, "0.12", "local-water-usage")
            mutable.value = mutable.value.copy(page = Page.ORDERS, waterDevice = null, waterOutlet = null,
                order = LocalOrder("local-runtime-owner", "分组测试洗衣机", "单脱水", "1.00", PayMethod.ALIPAY, OrderPhase.CLOSED),
                waterSession = WaterSession("local-runtime-owner", water, outlet, WaterPhase.USING, lastStatus = status))
        }
        awaitText("我的订单")
        click("洗衣")
        scrollToText("分组测试洗衣机")
        assertNull(find(automation.rootInActiveWindow) { it.text?.toString() == "饮水进行中 · 查看或结束" })
        scrollToText("全部", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        click("饮水")
        awaitText("饮水进行中 · 查看或结束")
        assertNull(find(automation.rootInActiveWindow) { it.text?.toString() == "分组测试洗衣机" })
        capture("orders-business-groups")
        click("查看饮水记录")
        awaitText("正在用水")
        assertEquals(water, vm.state.value.waterDevice)
        assertEquals(outlet, vm.state.value.waterOutlet)
        assertFalse(calls.contains("/goods/water/unlock"))
        assertFalse(calls.contains("/goods/endWater"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun waterControlsKeepTheirScrollPositionDuringPollingAndForegroundProgress() {
        val context = instrumentation.targetContext
        val appearance = ThemePreferences(context)
        val original = appearance.settings
        try {
            for (style in BottomBarStyle.entries) {
                instrumentation.runOnMainSync { appearance.update(original.copy(bottomBar = style)) }
                withLocalSession(waterDevice = true, waterRunning = true) { vm, calls ->
                    val outlet = WaterOutlet("local-water-sku", "常温水", "0.3 元/升")
                    val water = WaterDevice("local-water-device", "老校区3号楼1楼直饮机冷水", "LOCAL-WATER-IMEI",
                        "water-shop", "3100001", listOf(outlet), true,
                        announcement = (1..14).joinToString("\n") { "本地使用提示 $it：请准备好容器，结束后确认停止出水。" })
                    val mutable = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>
                    instrumentation.runOnMainSync {
                        val status = WaterStatus(2, 2, "0.12", "local-water-usage")
                        mutable.value = mutable.value.copy(page = Page.WATER, waterDevice = water, waterOutlet = outlet, waterStatus = status,
                            waterSession = WaterSession("local-runtime-owner", water, outlet, WaterPhase.USING,
                                lastStatus = status, observedRunning = true))
                    }
                    awaitText(water.name)
                    capture("water-compact-header-${style.name.lowercase()}")
                    scrollToText("结束用水")
                    fun stopBounds(): Rect {
                        val node = awaitText("结束用水")
                        assertTrue("Read current accessibility bounds", node.refresh())
                        return Rect().also(node::getBoundsInScreen)
                    }
                    val before = stopBounds()
                    val previousReads = calls.count { it == "/goods/water/sync" }
                    instrumentation.runOnMainSync { vm.refreshWater(silent = true) }
                    await { calls.count { it == "/goods/water/sync" } >= previousReads + 3 && !vm.state.value.busy }
                    instrumentation.waitForIdleSync()
                    val afterPolls = stopBounds()
                    assertTrue("Background polling moved the controls: $style", kotlin.math.abs(before.centerY() - afterPolls.centerY()) <= 8)
                    instrumentation.runOnMainSync { mutable.value = mutable.value.copy(busy = true, progress = "本地布局检查") }
                    awaitText("本地布局检查")
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(300)
                    val duringProgress = stopBounds()
                    capture("water-foreground-progress-${style.name.lowercase()}")
                    assertTrue("Progress changed the scroll layout: $style; before=$afterPolls after=$duringProgress",
                        kotlin.math.abs(afterPolls.centerY() - duringProgress.centerY()) <= 8)
                    instrumentation.runOnMainSync { mutable.value = mutable.value.copy(busy = false, progress = "") }
                    capture("water-scroll-preserved-${style.name.lowercase()}")
                    assertFalse(calls.contains("/goods/water/unlock"))
                }
            }
        } finally { instrumentation.runOnMainSync { appearance.update(original) } }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun settingsServiceSwitchesPersistAndKeepNavigationStable() {
        val context = instrumentation.targetContext
        val appearance = ThemePreferences(context)
        val original = appearance.settings
        try {
            for (style in BottomBarStyle.entries) {
                instrumentation.runOnMainSync { appearance.update(original.copy(bottomBar = style, laundryEnabled = true, waterEnabled = true, swipeNavigation = true)) }
                withLocalSession { vm, _ ->
                    click("设置导航")
                    val themeBounds = Rect().also(awaitText("主题设置")::getBoundsInScreen)
                    val laundryBounds = Rect().also(awaitText("洗衣功能开关")::getBoundsInScreen)
                    val waterBounds = Rect().also(awaitText("饮水功能开关")::getBoundsInScreen)
                    assertTrue(themeBounds.top < laundryBounds.top && laundryBounds.top < waterBounds.top)
                    assertTrue(ThemePreferences(context).settings.laundryEnabled)
                    assertTrue(ThemePreferences(context).settings.waterEnabled)
                    capture("settings-01-default-${style.name.lowercase()}")

                    click("饮水功能开关")
                    await { !ThemePreferences(context).settings.waterEnabled }
                    await { find(automation.rootInActiveWindow) { it.contentDescription?.contains("饮水导航") == true } == null }
                    awaitText("洗衣导航")
                    click("洗衣功能开关")
                    await { !ThemePreferences(context).settings.laundryEnabled }
                    await { find(automation.rootInActiveWindow) { it.contentDescription?.contains("洗衣导航") == true } == null }
                    awaitText("订单导航"); awaitText("设置导航")
                    capture("settings-02-both-hidden-${style.name.lowercase()}")

                    click("主题设置")
                    awaitText("主题模式")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitText("饮水功能开关")
                    val orderTab = Rect().also(awaitText("订单导航")::getBoundsInScreen)
                    val down = SystemClock.uptimeMillis()
                    injectTouch(down, MotionEvent.ACTION_DOWN, orderTab.exactCenterX(), orderTab.exactCenterY())
                    SystemClock.sleep(180)
                    injectTouch(down, MotionEvent.ACTION_UP, orderTab.exactCenterX(), orderTab.exactCenterY())
                    awaitText("我的订单")
                    assertEquals(Page.ORDERS, vm.state.value.page)
                    SystemClock.sleep(350)
                    val viewport = Rect().also(automation.rootInActiveWindow::getBoundsInScreen)
                    val swipe = SystemClock.uptimeMillis()
                    val y = viewport.top + viewport.height() * .45f
                    val from = viewport.left + viewport.width() * .82f
                    val to = viewport.left + viewport.width() * .18f
                    injectTouch(swipe, MotionEvent.ACTION_DOWN, from, y)
                    for (step in 1..12) {
                        SystemClock.sleep(16)
                        injectTouch(swipe, MotionEvent.ACTION_MOVE, from + (to - from) * step / 12f, y)
                    }
                    injectTouch(swipe, MotionEvent.ACTION_UP, to, y)
                    awaitText("饮水功能开关")
                }
                // A new activity reads the saved switches and chooses Orders when both services are hidden.
                withLocalSession { vm, _ ->
                    awaitText("我的订单")
                    assertEquals(Page.ORDERS, vm.state.value.page)
                    assertNull(find(automation.rootInActiveWindow) { it.contentDescription?.contains("洗衣导航") == true })
                    assertNull(find(automation.rootInActiveWindow) { it.contentDescription?.contains("饮水导航") == true })
                    click("设置导航")
                    click("饮水功能开关")
                    await { ThemePreferences(context).settings.waterEnabled }
                    click("饮水导航")
                    awaitText("开始饮水")
                    awaitText("多喝热水～")
                    assertEquals(Page.WATER, vm.state.value.page)
                    capture("settings-03-water-only-${style.name.lowercase()}")
                    click("设置导航")
                    click("洗衣功能开关")
                    await { ThemePreferences(context).settings.laundryEnabled }
                    click("洗衣导航")
                    awaitText("开始洗衣")
                    assertEquals(Page.HOME, vm.state.value.page)
                }
            }
        } finally { instrumentation.runOnMainSync { appearance.update(original) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setLocalOrder(vm: LaundryViewModel) {
        instrumentation.runOnMainSync {
            val state = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<UiState>
            state.value = state.value.copy(page = Page.ORDERS, order = LocalOrder("local-runtime-owner",
                "本地测试洗衣机", "标准洗", "4.00", PayMethod.ALIPAY, OrderPhase.CREATED,
                orderId = "local-order-id", orderNo = "local-trade-no"))
        }
    }

    private fun localAlipayPayload() = "method=alipay.trade.app.pay&biz_content=" + URLEncoder.encode(
        """{"total_amount":"4.00","out_trade_no":"local-alipay-order"}""", "UTF-8") + "&sign=LOCAL-UNSIGNED-TEST"

    @Suppress("UNCHECKED_CAST")
    private fun withLocalSession(
        promotions: String = """[{"promotionType":7,"isUsed":null,"options":null,"participateList":null}]""",
        detailStatuses: List<Int> = listOf(0),
        cancelRejected: Boolean = false,
        allowPrepay: Boolean = false,
        waterDevice: Boolean = false,
        waterRunning: Boolean = false,
        waterAlipay: Boolean = false,
        waterPriorFailure: Boolean = false,
        test: (LaundryViewModel, CopyOnWriteArrayList<String>) -> Unit
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            acceptTermsIfNeeded()
            lateinit var vm: LaundryViewModel
            lateinit var state: MutableStateFlow<UiState>
            lateinit var api: LaundryApi
            lateinit var oldClient: OkHttpClient
            lateinit var initial: UiState
            val calls = CopyOnWriteArrayList<String>()
            val detailRequests = AtomicInteger()
            val running = AtomicBoolean(waterRunning)
            val preferences = instrumentation.targetContext.getSharedPreferences("laundry_vault", 0)
            val fixtureKey = "order_local-runtime-owner"
            val previousFixture = preferences.getString(fixtureKey, null)
            val waterFixtureKey = "water_local-runtime-owner"
            val previousWaterFixture = preferences.getString(waterFixtureKey, null)
            val clientField = LaundryApi::class.java.getDeclaredField("client").apply { isAccessible = true }
            scenario.onActivity { activity ->
                vm = MainActivity::class.java.getDeclaredMethod("getVm").apply { isAccessible = true }.invoke(activity) as LaundryViewModel
                state = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>
                api = LaundryViewModel::class.java.getDeclaredField("api").apply { isAccessible = true }.get(vm) as LaundryApi
                oldClient = clientField.get(api) as OkHttpClient
                initial = state.value
                val localClient = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath
                    calls.add(path)
                    val body = request.body as? FormBody
                    val fields = body?.let { form -> (0 until form.size).associate { form.name(it) to form.value(it) } }.orEmpty()
                    val response = when (path) {
                        "/goods/scan/v2" -> if (waterDevice) """{"code":0,"data":{"id":"local-water-device","categoryCode":"04"}}"""
                            else """{"code":0,"data":{"id":"local-device","categoryCode":"00"}}"""
                        "/goods/normal/details" -> if (waterDevice) """{"code":0,"data":{"goodsId":"local-water-device","name":"本地测试饮水机","imei":"LOCAL-WATER-IMEI","categoryCode":"04","shopId":"water-shop","orgId":3100001,"tradeMain":1,"payment":"after","isBluetooth":0,"supportAppUserFinish":true,"extAttr":"{\"flowmeter\":1}"}}"""
                            else """{"code":0,"data":{"goodsId":"local-device","name":"本地测试洗衣机","imei":"LOCAL-TEST-IMEI","categoryCode":"00","shopId":"b6da19d1-118b-4f68-967a-000000000001","orgId":3100001,"payment":"before","isBluetooth":0,"remainTime":0}}"""
                        "/goods/normal/skus" -> if (waterDevice) """{"code":0,"data":[{"skuId":"local-water-sku","name":"常温水","extAttr":"{\"level\":[{\"price\":0.3}]}"}]}"""
                            else """{"code":0,"data":[{"skuId":"local-sku","name":"标准洗","price":"4.00","scanAvailable":true}]}"""
                        "/goods/water/sync" -> if (running.get()) """{"code":0,"data":{"status":2,"workStatus":2,"amount":"0.12","identify":"local-water-usage"}}"""
                            else if (waterPriorFailure) """{"code":0,"data":{"status":6,"workStatus":1,"amount":"0","identify":"previous-failed-usage"}}"""
                            else """{"code":0,"data":{"status":3,"workStatus":1,"amount":"0.15","identify":"local-water-usage"}}"""
                        "/payChannelRoute/afterPay" -> if (waterAlipay)
                            """{"code":0,"data":[{"method":15,"methodName":"支付宝支付","isThird":0,"needOpenFlag":true,"defaultFlag":true},{"method":53,"methodName":"云闪付免密支付"}]}"""
                            else """{"code":0,"data":[{"method":2,"needOpenFlag":false,"isThird":0}]}"""
                        "/popup/shop" -> """{"code":0,"data":[]}"""
                        "/alipay/signUrl/pinpoint" -> """{"code":0,"data":{"url":"https://openapi.alipay.com/gateway.do?app_id=fixture&sign=fixture"}}"""
                        "/goods/endWater" -> { running.set(false); """{"code":0,"data":true}""" }
                        "/order/afterPay/creating" -> """{"code":0,"data":{"orderId":"local-water-bill","orderNo":"local-water-bill-no","payType":2,"orderStatus":3}}"""
                        "/appoint/checkMachineCanUse" -> """{"code":0,"data":true}"""
                        "/trade/preview/v4" -> if (fields["isAutoSelectPromotion"] == "0" && fields["forbiddenPromotions"]?.contains("3100001") == true)
                            """{"code":0,"data":{"realPrice":"4.00","tradeType":null,"items":[{"goodsId":"local-device","skuId":"local-sku","amount":1}],"promotions":$promotions}}"""
                        else """{"code":1,"msg":"本地测试：报价参数不正确"}"""
                        "/payChannelRoute/prePay" -> if (fields["shopId"] == "3100001" && fields["goodsId"] == "local-device" && fields["imei"] == "LOCAL-TEST-IMEI")
                            """{"code":0,"data":[{"method":13,"autoPayState":0},{"method":2,"autoPayState":0}]}"""
                        else """{"code":1,"msg":"Failed to convert property 'shopId' to java.lang.Long"}"""
                        "/order/detail" -> if (fields["orderId"] == "local-water-bill")
                            """{"code":0,"data":{"id":"local-water-bill","orderNo":"local-water-bill-no","payType":2,"orderStatus":3,"payPrice":"0.15"}}"""
                        else if (fields["orderId"] == "local-order-id") {
                            val status = detailStatuses[detailRequests.getAndIncrement().coerceAtMost(detailStatuses.lastIndex)]
                            """{"code":0,"data":{"id":"local-order-id","orderNo":"local-trade-no","orderStatus":$status}}"""
                        } else """{"code":1,"msg":"Unexpected local order"}"""
                        "/order/pay_status" -> """{"code":0,"data":false}"""
                        "/order/cancel" -> if (cancelRejected) """{"code":3122,"msg":"只有未支付订单才能取消"}"""
                            else """{"code":0,"data":"已取消"}"""
                        "/pay/unify/prePay" -> if (allowPrepay) JsonObject().apply {
                            addProperty("code", 0)
                            add("data", JsonObject().apply { addProperty("prepayParam", localAlipayPayload()) })
                        }.toString() else """{"code":1,"msg":"Local payment is disabled"}"""
                        else -> """{"code":1,"msg":"本地测试已阻止非预期接口"}"""
                    }
                    // Never call chain.proceed: no request reaches a socket or the production service.
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture")
                        .body(response.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
                }.build()
                clientField.set(api, localClient)
                state.value = initial.copy(loggedIn = true, phoneLabel = "本地模拟账户", page = Page.HOME)
            }
            try { test(vm, calls) }
            finally {
                instrumentation.runOnMainSync {
                    state.value = initial
                    clientField.set(api, oldClient)
                }
                preferences.edit().apply {
                    if (previousFixture == null) remove(fixtureKey) else putString(fixtureKey, previousFixture)
                    if (previousWaterFixture == null) remove(waterFixtureKey) else putString(waterFixtureKey, previousWaterFixture)
                }.commit()
            }
        }
    }

    private fun acceptTermsIfNeeded() {
        await {
            find(automation.rootInActiveWindow) { it.text?.toString() == "免责声明与用户守则" } != null ||
                find(automation.rootInActiveWindow) { it.text?.toString() == "手机号登录" } != null ||
                find(automation.rootInActiveWindow) { it.text?.toString() == "开始洗衣" } != null
        }
        if (find(automation.rootInActiveWindow) { it.text?.toString() == "免责声明与用户守则" } == null) return
        for (attempt in 0 until 40) {
            if (find(automation.rootInActiveWindow) { it.isEditable } != null) break
            awaitNode { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        val editor = awaitNode { it.isEditable }
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, AgreementPolicy.CONFIRMATION)
        }))
        click("确认并进入")
        await { find(automation.rootInActiveWindow) { it.text?.toString() == "免责声明与用户守则" } == null }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for Android state")
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await { found = find(automation.rootInActiveWindow, predicate); found != null }
        return requireNotNull(found)
    }

    private fun awaitText(text: String) = awaitNode {
        it.text?.contains(text) == true || it.contentDescription?.contains(text) == true
    }

    private fun scrollToText(text: String, direction: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
        repeat(16) {
            if (find(automation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text } != null) return
            awaitNode { it.isScrollable }.performAction(direction)
            SystemClock.sleep(250)
        }
        awaitText(text)
    }

    private fun savedDonationImages(): Set<Uri> {
        val images = mutableSetOf<Uri>()
        instrumentation.targetContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ?",
            arrayOf("简洗-支付宝收款码%", instrumentation.targetContext.packageName), null
        )?.use { cursor ->
            while (cursor.moveToNext()) images.add(ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
        }
        return images
    }

    private fun click(text: String) {
        await {
            var node = find(automation.rootInActiveWindow) {
                it.text?.toString() == text || it.contentDescription?.toString() == text
            }
            while (node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }

    private fun injectTouch(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(180)
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Android screenshot unavailable" }
        try { File(screenshots, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
