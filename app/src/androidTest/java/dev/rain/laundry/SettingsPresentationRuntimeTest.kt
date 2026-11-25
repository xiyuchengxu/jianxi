package dev.rain.laundry

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.cloud.CloudReminderUiState
import dev.rain.laundry.core.SavedDevice
import dev.rain.laundry.core.SavedDeviceKind
import dev.rain.laundry.i18n.AppLanguage
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.LanguagePreferences
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real settings components mounted behind the consent gate. No model, remote binding or API is created. */
@RunWith(AndroidJUnit4::class)
class SettingsPresentationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun settingsShowsOnlyNamesAndScannerRemainsIndependent() = withGate { scenario ->
        val initial = ThemeSettings(splashEnabled = false)
        val latest = AtomicReference(initial)
        val changes = AtomicInteger()
        val navigation = AtomicInteger()
        mount(scenario) {
            var settings by remember { mutableStateOf(initial) }
            SettingsScreen(settings, {
                settings = it; latest.set(it); changes.incrementAndGet()
            }, { navigation.incrementAndGet() }, true, { navigation.incrementAndGet() })
        }
        awaitNode { it.contentDescription?.toString() ==
            appText(R.string.msg_9d8fb9c546b1, appText(R.string.settings_scanner_title)) }
        for (description in listOf("简体中文", appText(R.string.msg_79080acb89b3),
            appText(R.string.msg_ecf99c00e14f),
            appText(R.string.msg_5f63b1959faf, appText(R.string.msg_bc982c81c510)),
            appText(R.string.msg_5f63b1959faf, appText(R.string.msg_6edae041a323)),
            appText(R.string.settings_scanner_help))) {
            assertNull(find { it.text?.contains(description) == true })
        }
        capture("settings-names-only.png")
        click(appText(R.string.msg_9d8fb9c546b1, appText(R.string.settings_scanner_title)))
        await { !latest.get().scannerEnabled }
        assertTrue(latest.get().laundryEnabled)
        assertTrue(latest.get().waterEnabled)
        click(appText(R.string.msg_9d8fb9c546b1, appText(R.string.msg_bc982c81c510)))
        await { !latest.get().laundryEnabled }
        assertFalse(latest.get().scannerEnabled)
        assertTrue(latest.get().waterEnabled)
        assertEquals(2, changes.get())
        assertEquals(0, navigation.get())
    }

    @Test fun originalThemeContentIsVisibleWithoutOpeningANewFold() = withGate { scenario ->
        val changes = AtomicInteger()
        scenario.onActivity { activity -> activity.setContent {
            LaundryTheme(ThemeSettings(splashEnabled = false)) {
                Surface(Modifier.fillMaxSize()) {
                    ThemeScreen(ThemeSettings(splashEnabled = false), { changes.incrementAndGet() },
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp))
                }
            }
        } }
        awaitNode { it.text?.contains(appText(R.string.theme_design_style_title)) == true }
        assertNull(find { it.text?.contains(appText(R.string.font_yunzhuan_title)) == true })
        capture("theme-restored.png")
        assertEquals(0, changes.get())
    }

    @Test fun miuixStyleCanBeSelectedFromThemeSettings() = withGate { scenario ->
        val initial = ThemeSettings(splashEnabled = false)
        val latest = AtomicReference(initial)
        scenario.onActivity { activity -> activity.setContent {
            var settings by remember { mutableStateOf(initial) }
            LaundryTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    ThemeScreen(settings, { settings = it; latest.set(it) },
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp))
                }
            }
        } }
        awaitNode { it.text?.toString() == "MIUIX" }
        click("MIUIX")
        await { latest.get().designStyle == DesignStyle.MIUIX }
        assertEquals(DesignStyle.MIUIX, latest.get().designStyle)
    }

    @Test fun originalCloudControlsAndUncheckedConsentAreVisible() = withGate { scenario ->
        val calls = AtomicInteger()
        mount(scenario) {
            CloudReminderSettings(CloudReminderUiState(), true,
                { _, _, _, _ -> calls.incrementAndGet() },
                { calls.incrementAndGet() }, { calls.incrementAndGet() },
                { calls.incrementAndGet() }, { calls.incrementAndGet() },
                { calls.incrementAndGet() }, { calls.incrementAndGet() })
        }
        awaitNode { it.isEditable }
        capture("cloud-restored.png")
        var consent = find { it.isCheckable }
        repeat(6) {
            if (consent == null) {
                find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(350)
                consent = find { it.isCheckable }
            }
        }
        assertNotNull(consent)
        assertFalse(requireNotNull(consent).isChecked)
        assertEquals(0, calls.get())
    }

    private fun mount(scenario: ActivityScenario<MainActivity>, content: @Composable () -> Unit) {
        scenario.onActivity { activity -> activity.setContent {
            LaundryTheme(ThemeSettings(splashEnabled = false)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                }
            }
        } }
    }

    @Test fun waterSavedEntryFollowsScanAndSurvivesScannerBeingDisabled() = withGate { scenario ->
        val opens = AtomicInteger()
        fun render(scanning: Boolean) = mount(scenario) {
            WaterScreen(UiState(), {}, {}, {}, {}, {}, {}, {},
                openSavedDevices = { opens.incrementAndGet() }, scannerEnabled = scanning)
        }
        val scanTitle = appText(R.string.msg_dd3246e28288)
        val savedTitle = appText(R.string.saved_device_title_water)
        render(true)
        val scan = awaitNode { it.text?.toString() == scanTitle }
        val saved = awaitNode { it.text?.contains(savedTitle) == true }
        val scanBounds = Rect().also(scan::getBoundsInScreen)
        val savedBounds = Rect().also(saved::getBoundsInScreen)
        assertTrue("Saved water devices must be below the scan control", savedBounds.top >= scanBounds.bottom)
        assertNull(find { it.text?.contains(appText(R.string.msg_8032591e13e5)) == true })
        capture("water-page.png")
        render(false)
        await { find { it.text?.toString() == scanTitle } == null }
        awaitNode { it.text?.contains(savedTitle) == true }
        awaitNode { it.text?.toString() == appText(R.string.msg_fc984db26cf9) }
        var savedAction: AccessibilityNodeInfo? = awaitNode { it.text?.contains(savedTitle) == true }
        while (savedAction != null && !savedAction.isClickable) savedAction = savedAction.parent
        assertTrue(requireNotNull(savedAction).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await { opens.get() == 1 }
        capture("water-scan-disabled.png")
    }

    @Test fun dryerSavedEntryAndSheetStaySeparateFromLaundry() = withGate { scenario ->
        val devices = listOf(
            SavedDevice(owner = "owner", kind = SavedDeviceKind.LAUNDRY.wire, deviceId = "washer-1",
                codeKey = "IMEI", codeValue = "WASHER123", name = "Washer One", lastUsedAt = 2),
            SavedDevice(owner = "owner", kind = SavedDeviceKind.DRYER.wire, deviceId = "dryer-1",
                codeKey = "IMEI", codeValue = "DRYER123", name = "Dryer One", lastUsedAt = 1)
        )
        val selected = AtomicReference<SavedDevice?>(null)
        mount(scenario) {
            var sheetOpen by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SavedDevicesEntry(SavedDeviceKind.LAUNDRY, devices, true, {})
                SavedDevicesEntry(SavedDeviceKind.DRYER, devices, true, { sheetOpen = true })
            }
            if (sheetOpen) SavedDevicesSheet(SavedDeviceKind.DRYER, devices, false, null,
                { selected.set(it) }, {}, { _, _ -> }, { sheetOpen = false })
        }
        awaitNode { it.text?.contains(appText(R.string.saved_device_entry_count_laundry, 1)) == true }
        awaitNode { it.text?.contains(appText(R.string.saved_device_entry_count_dryer, 1)) == true }
        var entry: AccessibilityNodeInfo? = awaitNode {
            it.isVisibleToUser && it.text?.contains(appText(R.string.saved_device_entry_count_dryer, 1)) == true
        }
        while (entry != null && !entry.isClickable) entry = entry.parent
        assertTrue(requireNotNull(entry).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitNode { it.text?.toString() == "Dryer One" }
        assertNull(find { it.text?.toString() == "Washer One" })
        assertNotNull(find { it.text?.toString() == "Dryer One" })
        capture("dryer-saved-devices.png")
        click("Dryer One")
        await { selected.get() != null }
        assertEquals("dryer-1", selected.get()?.deviceId)
        assertEquals(SavedDeviceKind.DRYER, selected.get()?.savedKind)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "settings-presentation").apply { mkdirs() }
        val bitmap = requireNotNull(automation.takeScreenshot())
        FileOutputStream(File(directory, name)).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun withGate(block: (ActivityScenario<MainActivity>) -> Unit) {
        val preferences = listOf("usage_agreement", "appearance", "app_language")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val original = preferences.mapValues { HashMap(it.value.all) }
        val oldLocales = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else null
        try {
            assertTrue(preferences.getValue("usage_agreement").edit().clear().commit())
            assertTrue(preferences.getValue("appearance").edit().putBoolean("splash_enabled", false).commit())
            instrumentation.runOnMainSync { LanguagePreferences.set(context, AppLanguage.SIMPLIFIED_CHINESE) }
            AppStrings.invalidate()
            ActivityScenario.launch(MainActivity::class.java).use(block)
        } finally {
            if (Build.VERSION.SDK_INT >= 33 && oldLocales != null) instrumentation.runOnMainSync {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(oldLocales)
            }
            original.forEach { (name, values) -> restore(preferences.getValue(name), values) }
            AppStrings.invalidate()
        }
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(editor.commit())
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(automation.rootInActiveWindow)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for settings presentation")
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = find(predicate); result != null }
        return requireNotNull(result)
    }

    private fun click(text: String) {
        await {
            var node = find { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
            while (node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
}
