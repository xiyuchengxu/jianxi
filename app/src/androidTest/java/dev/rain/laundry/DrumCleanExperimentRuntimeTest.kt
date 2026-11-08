package dev.rain.laundry

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.experiment.DrumCleanExperimentActivity
import dev.rain.laundry.experiment.DrumCleanFixtures
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI checks use invented fixtures and require the dedicated emulator to be offline. */
@RunWith(AndroidJUnit4::class)
class DrumCleanExperimentRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun forceShowsHiddenEntryButStillCannotObtainASku() = withExperiment {
        await { find("实验入口：隐藏") != null }
        assertNull(find("查看本地分流"))
        click("强制展示诊断入口开关")
        await { find("实验入口：显示") != null }
        assertNotNull(find("MISSING_SKU"))
        click("查看本地分流")
        await { find("未创建订单，未付款，未启动机器") != null }
        capture("hidden-forced")
    }

    @Test fun zeroPriceStopsAtLocalGuideAndDisplaysExplicitMachineAndAmount() = withExperiment {
        select("zero")
        click("查看本地分流")
        await { find("模拟金额：¥0.00") != null }
        assertNotNull(find("DEMO-IMEI-A"))
        assertNotNull(find("LOCAL_ZERO_ROUTE"))
        assertNotNull(find("未创建订单，未付款，未启动机器"))
        capture("zero-local-only")
    }

    @Test fun paidPriceStopsAtLocalCashierAndNeverOffersPayment() = withExperiment {
        select("paid")
        click("查看本地分流")
        await { find("模拟金额：¥1.50") != null }
        assertNotNull(find("LOCAL_PAID_ROUTE"))
        assertNull(find("确认付款"))
        assertNull(find("确认启动"))
        capture("paid-local-only")
    }

    @Test fun serverRejectionSurvivesForceAndRedisplay() = withExperiment {
        select("rejected")
        click("强制展示诊断入口开关")
        click("查看本地分流")
        await { find("SERVER_REJECTED") != null && find("未创建订单，未付款，未启动机器") != null }
        assertNotNull(find("显示入口不能获得资格"))
        assertNull(find("模拟金额：¥"))
        capture("server-rejected-forced")
    }

    private fun withExperiment(block: () -> Unit) {
        assumeTrue("Dedicated emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("Use airplane mode for the offline experiment", 1,
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        val keys = listOf("laundry_vault", "appearance", "usage_agreement")
        val previous = keys.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val intent = Intent(context, DrumCleanExperimentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<DrumCleanExperimentActivity>(intent).use {
            await { find("仅模拟数据") != null }
            block()
        }
        keys.forEach { assertEquals("Experiment changed $it", previous[it], context.getSharedPreferences(it, 0).all) }
    }

    private fun select(id: String) {
        repeat(DrumCleanFixtures.cases.indexOfFirst { it.id == id }.also { assertTrue(it >= 0) }) { click("下一个场景") }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(60)
        }
        fail("Timed out waiting for offline experiment UI")
    }

    private fun find(text: String): AccessibilityNodeInfo? {
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
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = File(context.getExternalFilesDir(null), "drum-clean-experiment").apply { mkdirs() }
        try { File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
