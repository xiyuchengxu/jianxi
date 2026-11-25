package dev.rain.laundry

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.ui.AppButton
import dev.rain.laundry.ui.AppCard
import dev.rain.laundry.ui.ControlTransparencyHost
import dev.rain.laundry.ui.DesignStyle
import dev.rain.laundry.ui.LaundryTheme
import dev.rain.laundry.ui.LocalControlOpacity
import dev.rain.laundry.ui.ThemeSettings
import dev.rain.laundry.ui.useSystemWallpaperWindow
import dev.rain.laundry.ui.wallpaperBrightnessOverlay
import dev.rain.laundry.ui.ThemePreferences
import dev.rain.laundry.ui.WallpaperBackgroundSettings
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Uses the currently installed system wallpaper on a logged-out test device. */
@RunWith(AndroidJUnit4::class)
class WallpaperBackgroundRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test fun materialWallpaperSwitchBrightnessAndRecreation() = exerciseWallpaper(DesignStyle.MATERIAL)
    @Test fun miuixWallpaperSwitchBrightnessAndRecreation() = exerciseWallpaper(DesignStyle.MIUIX)

    @Test fun stationaryHoldEditsOnlyNestedButtonAndShortTapStillClicks() {
        assumeTrue("Requires a logged-out test install", SessionVault(context).token().isBlank())
        val overrides = context.getSharedPreferences("control_transparency_overrides", Context.MODE_PRIVATE)
        val card = "qa.gesture.parent"
        val button = "qa.gesture.child"
        val previous = listOf(card, button).associateWith { overrides.all[it] as? Float }
        val clicks = AtomicInteger()
        val renderedOpacity = AtomicReference(0f)
        val label = "Nested opacity test action"
        try {
            assertTrue(overrides.edit().putFloat(card, .10f).remove(button).commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    LaundryTheme(ThemeSettings(designStyle = DesignStyle.MIUIX, controlTransparency = .35f)) {
                        ControlTransparencyHost {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                AppCard(controlId = card) {
                                    AppButton(onClick = { clicks.incrementAndGet() }, controlId = button) {
                                        val opacity = LocalControlOpacity.current
                                        SideEffect { renderedOpacity.set(opacity) }
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                } }
                fun touchButton(hold: Boolean) {
                    val node = awaitNode { it.text?.toString() == label }
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val at = "${bounds.centerX()} ${bounds.centerY()}"
                    val command = if (hold) "input swipe $at $at ${ViewConfiguration.getLongPressTimeout() + 200}"
                        else "input tap $at"
                    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
                }
                await { abs(renderedOpacity.get() - .65f) < .001f }
                touchButton(hold = true)
                val slider = awaitNode { it.rangeInfo?.min == 0f && it.rangeInfo?.max == 1f && it.isEnabled }
                val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .8f) }
                assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                await { abs(renderedOpacity.get() - .2f) < .001f }
                assertEquals(.8f, overrides.getFloat(button, -1f), .001f)
                assertEquals(.10f, overrides.getFloat(card, -1f), .001f)
                click(awaitNode { it.text?.toString() == appText(R.string.control_follow_global) })
                await { !overrides.contains(button) && abs(renderedOpacity.get() - .65f) < .001f }
                assertEquals(.10f, overrides.getFloat(card, -1f), .001f)
                assertEquals("Editing must not trigger the button", 0, clicks.get())
                touchButton(hold = false)
                await { clicks.get() == 1 }
            }
        } finally {
            val edit = overrides.edit()
            previous.forEach { (id, value) -> if (value == null) edit.remove(id) else edit.putFloat(id, value) }
            assertTrue(edit.commit())
        }
    }

    private fun exerciseWallpaper(style: DesignStyle) {
        assumeTrue("Requires a logged-out dedicated test install", SessionVault(context).token().isBlank())
        assumeTrue("System wallpaper service unavailable",
            context.getSystemService(WallpaperManager::class.java)?.isWallpaperSupported == true)
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val savedAppearance = HashMap(appearance.all)
        try {
            assertTrue(appearance.edit().putString("design_style", style.name).putString("mode", "LIGHT")
                .putString("animation", "NONE").putBoolean("splash_enabled", false)
                .putBoolean("wallpaper_background_enabled", false)
                .putFloat("wallpaper_background_brightness", 0f).commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                mountControls(scenario)
                val switch = awaitNode { it.isCheckable && it.isEnabled }
                val original = windowState(scenario)
                assertFalse(original.showsWallpaper)
                click(switch)
                await {
                    appearance.getBoolean("wallpaper_background_enabled", false) && windowState(scenario).showsWallpaper
                }
                val enabled = windowState(scenario)
                assertEquals(PixelFormat.TRANSLUCENT, enabled.format)
                if (Build.VERSION.SDK_INT >= 34) assertEquals(false, enabled.touchForwarding)

                SystemClock.sleep(250)
                val originalLuminance = backgroundLuminance()
                setBrightness(-.25f)
                await { kotlin.math.abs(backgroundLuminance() - originalLuminance * .75) < 6 }
                setBrightness(-1f)
                await { appearance.getFloat("wallpaper_background_brightness", 0f) == -1f }
                SystemClock.sleep(250)
                val dark = backgroundLuminance()
                setBrightness(1f)
                await { appearance.getFloat("wallpaper_background_brightness", 0f) == 1f }
                await { backgroundLuminance() > dark + 12 }

                scenario.recreate()
                mountControls(scenario)
                await { windowState(scenario).showsWallpaper }
                val restoredSlider = awaitNode(::isBrightnessSlider)
                assertEquals(1f, requireNotNull(restoredSlider.rangeInfo).current, .001f)
                val resetLabel = appText(R.string.wallpaper_background_reset)
                click(awaitNode { it.text?.toString() == resetLabel })
                await {
                    appearance.getFloat("wallpaper_background_brightness", 1f) == 0f &&
                        find(::isBrightnessSlider)?.rangeInfo?.current == 0f
                }
                click(awaitNode { it.isCheckable && it.isEnabled })
                await {
                    !appearance.getBoolean("wallpaper_background_enabled", true) && !windowState(scenario).showsWallpaper
                }
                val restored = windowState(scenario)
                assertEquals(original.format, restored.format)
                assertEquals(original.touchForwarding, restored.touchForwarding)
            }
        } finally {
            restore(appearance, savedAppearance)
        }
    }

    // Mount the production controls without the long, independently scrolling theme catalogue.
    // Both binding and window rendering use the same settings and compositor as the full page.
    private fun mountControls(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.setContent {
                val appearance = remember { ThemePreferences(activity) }
                val settings = appearance.settings
                LaundryTheme(settings) {
                    val visible = useSystemWallpaperWindow(settings.wallpaperBackgroundEnabled)
                    val page = if (visible) wallpaperBrightnessOverlay(settings.wallpaperBackgroundBrightness)
                        else MaterialTheme.colorScheme.background
                    Box(Modifier.fillMaxSize().background(page), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth(.85f).background(MaterialTheme.colorScheme.surface)) {
                            WallpaperBackgroundSettings(settings, appearance::update)
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
    }

    private data class WindowState(val showsWallpaper: Boolean, val format: Int, val touchForwarding: Boolean?)

    private fun windowState(scenario: ActivityScenario<MainActivity>): WindowState {
        lateinit var result: WindowState
        scenario.onActivity { activity ->
            val attributes = activity.window.attributes
            result = WindowState(attributes.flags and FLAG_SHOW_WALLPAPER != 0, attributes.format,
                if (Build.VERSION.SDK_INT >= 34) attributes.areWallpaperTouchEventsEnabled() else null)
        }
        return result
    }

    private fun isBrightnessSlider(node: AccessibilityNodeInfo): Boolean {
        val range = node.rangeInfo ?: return false
        return range.min == -1f && range.max == 1f && node.isEnabled
    }

    private fun setBrightness(value: Float) {
        val node = awaitNode(::isBrightnessSlider)
        val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
        assertTrue(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
        instrumentation.waitForIdleSync()
    }

    // Both layouts reserve horizontal padding beside their cards. These pixels contain the
    // composited wallpaper, outside the text, slider thumb, app bar and system navigation region.
    private fun backgroundLuminance(): Double {
        instrumentation.waitForIdleSync()
        val image = requireNotNull(automation.takeScreenshot())
        return try {
            var sum = 0.0
            var count = 0
            for (x in listOf(image.width / 100, image.width * 99 / 100)) {
                for (index in 0..24) {
                    val y = image.height * (28 + index * 2) / 100
                    val pixel = image.getPixel(x, y)
                    sum += .2126 * Color.red(pixel) + .7152 * Color.green(pixel) + .0722 * Color.blue(pixel)
                    count++
                }
            }
            sum / count
        } finally {
            image.recycle()
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(automation.rootInActiveWindow)
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = find(predicate); result != null }
        return requireNotNull(result)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for wallpaper controls or window state.")
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
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
