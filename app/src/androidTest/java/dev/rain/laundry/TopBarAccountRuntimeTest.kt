package dev.rain.laundry

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.ui.DesignStyle
import dev.rain.laundry.ui.LaundryApp
import dev.rain.laundry.ui.LaundryTheme
import dev.rain.laundry.ui.PageAnimation
import dev.rain.laundry.ui.ThemeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopBarAccountRuntimeTest {
    private val testDevice get() = InstrumentationRegistry.getInstrumentation()
    private val ui get() = testDevice.uiAutomation

    @Test fun materialPlacesLogoutInSettings() = exercise(DesignStyle.MATERIAL)
    @Test fun miuixPlacesLogoutInSettings() = exercise(DesignStyle.MIUIX)

    @Suppress("UNCHECKED_CAST")
    private fun exercise(style: DesignStyle) {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        assumeTrue(SessionVault(testDevice.targetContext).token().isEmpty())
        val settings = ThemeSettings(designStyle = style, animation = PageAnimation.NONE, splashEnabled = false)
        val about = appText(R.string.msg_c1cb5f40de7c)
        val settingsLabel = appText(R.string.msg_df3d58c7d84b)
        val phone = appText(R.string.msg_b4f947a472c7)
        val logout = appText(R.string.settings_log_out)
        val prompt = appText(R.string.msg_b6af1a5049fc)
        val cancel = appText(R.string.msg_2cd0f3be8738)
        val close = appText(R.string.scanner_close)
        val settingsTab = appText(R.string.msg_8ffbad496815, settingsLabel)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: MutableStateFlow<UiState>
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LaundryViewModel::class.java]
                state = LaundryViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(model) as MutableStateFlow<UiState>
                activity.setContent { LaundryTheme(settings) { LaundryApp(model, {}, {}, settings = settings) } }
            }
            node(phone)
            checkTopRight(about)
            val settingsBounds = bounds(node(settingsLabel))
            assertTrue("Login settings must be on the left", settingsBounds.centerX() < screen().centerX())
            tap(settingsLabel)
            node(appText(R.string.msg_788db1cfec2a))
            assertNull(find(logout))
            tap(close)
            node(phone)
            tap(about)
            node(close)
            tap(close)
            node(phone)

            // Only publish presentation state; the vault stays empty, so neither login nor an API call is needed.
            val account = "QA topbar account"
            scenario.onActivity { state.value = UiState(loggedIn = true, phoneLabel = account) }
            node(account)
            checkTopRight(about)
            assertNull(find(logout))
            assertNull(find(appText(R.string.msg_498e1d59b4d7)))
            tap(about)
            node(close)
            tap(close)
            node(account)

            tap(settingsTab)
            node(appText(R.string.msg_788db1cfec2a))
            scrollTo(logout)
            tap(logout)
            node(prompt)
            tap(cancel)
            // The dialog and the underlying settings button use the same logout label.
            // Wait for the settings window to regain accessibility focus after dismissal.
            node(settingsTab)
            assertNull(find(prompt))
            assertTrue("Cancel must keep the session", state.value.loggedIn)
            tap(logout)
            node(prompt)
            tap(logout)
            node(phone)
            assertFalse("Confirm must clear the session", state.value.loggedIn)
            checkTopRight(about)
        }
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun screen() = bounds(requireNotNull(ui.rootInActiveWindow))

    private fun nodes(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root != null) {
            if (root.isVisibleToUser) yield(root)
            for (index in 0 until root.childCount) yieldAll(nodes(root.getChild(index)))
        }
    }

    private fun find(label: String) = nodes(ui.rootInActiveWindow).firstOrNull {
        it.text?.toString() == label || it.contentDescription?.toString() == label
    }

    private fun node(label: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 7000
        do {
            find(label)?.let { return it }
            SystemClock.sleep(65)
        } while (SystemClock.uptimeMillis() < end)
        throw AssertionError("Visible control missing: $label")
    }

    private fun tap(label: String) {
        var target = node(label)
        while (!target.isClickable) target = requireNotNull(target.parent) { "Cannot click $label" }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        testDevice.waitForIdleSync()
    }

    private fun checkTopRight(about: String) {
        val button = bounds(node(about))
        val mid = screen().centerX()
        assertTrue("About must be on the right", button.centerX() > mid)
        val neighbors = nodes(ui.rootInActiveWindow).filter { it.isClickable }.map(::bounds).filter {
            it.centerX() > mid && it.centerY() in button.top..button.bottom && !it.contains(button.centerX(), button.centerY())
        }.toList()
        assertTrue("Unexpected actions alongside About: $neighbors", neighbors.isEmpty())
    }

    private fun scrollTo(label: String) {
        repeat(5) {
            if (find(label) != null) return
            val area = screen()
            val x = area.centerX()
            val command = "input swipe $x ${area.height() * 3 / 4} $x ${area.height() / 3} 340"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
            testDevice.waitForIdleSync()
        }
        node(label)
    }
}
