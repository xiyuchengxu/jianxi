package dev.rain.laundry

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.SavedDevice
import dev.rain.laundry.core.SavedDeviceKind
import dev.rain.laundry.core.SavedDeviceStatus
import dev.rain.laundry.core.SavedDeviceStatusPhase
import dev.rain.laundry.i18n.AppLanguage
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.LanguagePreferences
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.ui.LaundryTheme
import dev.rain.laundry.ui.SavedDeviceStatusCard
import dev.rain.laundry.ui.SavedDevicesSheet
import dev.rain.laundry.ui.ThemeSettings
import dev.rain.laundry.ui.savedDeviceListStatusColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** Real sheets with synthetic snapshots only; the consent gate prevents any API model from starting. */
@RunWith(AndroidJUnit4::class)
class SavedDeviceStatusPresentationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val owner = "offline-status-presentation-owner"
    private val checkedAt = 1_700_000_000_000L
    private val phaseLabels = linkedMapOf(
        SavedDeviceStatusPhase.AVAILABLE to R.string.saved_status_list_available,
        SavedDeviceStatusPhase.USING to R.string.saved_status_list_unavailable,
        SavedDeviceStatusPhase.OCCUPIED to R.string.saved_status_list_unavailable,
        SavedDeviceStatusPhase.UNAVAILABLE to R.string.saved_status_list_unavailable,
        SavedDeviceStatusPhase.CHECKING to R.string.saved_status_list_checking,
        SavedDeviceStatusPhase.UNKNOWN to R.string.saved_status_list_unknown,
        SavedDeviceStatusPhase.ERROR to R.string.saved_status_list_failed
    )

    @Test fun laundryListHasDotAndConciseLabelWithQueryAndLastUsedTimes() = withFixture { scenario ->
        val device = record(SavedDeviceKind.LAUNDRY)
        val snapshot = mutableStateOf(observation(device))
        mount(scenario) {
            SavedDevicesSheet(SavedDeviceKind.LAUNDRY, listOf(device), false, null,
                {}, {}, { _, _ -> }, {}, status = snapshot.value,
                statuses = mapOf(SavedDeviceStatus.key(device) to snapshot.value))
        }
        for ((phase, labelId) in phaseLabels) {
            instrumentation.runOnMainSync { snapshot.value = snapshot.value.copy(phase = phase) }
            awaitText(appText(labelId))
            instrumentation.waitForIdleSync()
            val expectedColor = when (phase) {
                SavedDeviceStatusPhase.AVAILABLE -> Color(0xFF2E7D32)
                SavedDeviceStatusPhase.CHECKING, SavedDeviceStatusPhase.UNKNOWN -> Color(0xFF757575)
                else -> Color(0xFFC62828)
            }
            assertEquals(phase.name, expectedColor, savedDeviceListStatusColor(phase))
            assertNull(find { it.text?.contains("FORBIDDEN_SUBJECT") == true })
            assertNull(find { it.text?.contains("FORBIDDEN_DETAIL") == true })
            assertNull(find { it.text?.contains(appText(R.string.saved_status_remaining, 37)) == true })
            assertNull(find { it.text?.contains(appText(R.string.saved_status_laundry_available)) == true })
            assertNull(find { it.text?.contains(appText(R.string.saved_status_title)) == true })
            awaitText(lastUsed(device))
            val checked = queryTime()
            if (phase == SavedDeviceStatusPhase.CHECKING)
                await { find { it.text?.contains(checked) == true } == null }
            else awaitText(checked)
            capture("laundry-${phase.name.lowercase()}")
        }
    }

    @Test fun waterListNeverRendersPassedStatusOrQueryTime() = withFixture { scenario ->
        val device = record(SavedDeviceKind.WATER)
        val snapshot = mutableStateOf(observation(device))
        mount(scenario) {
            SavedDevicesSheet(SavedDeviceKind.WATER, listOf(device), false, null,
                {}, {}, { _, _ -> }, {}, status = snapshot.value,
                statuses = mapOf(SavedDeviceStatus.key(device) to snapshot.value))
        }
        awaitText(device.name)
        for (phase in SavedDeviceStatusPhase.entries) {
            instrumentation.runOnMainSync { snapshot.value = snapshot.value.copy(phase = phase) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            for (id in phaseLabels.values.toSet() + setOf(R.string.saved_status_water_available,
                R.string.saved_status_water_using, R.string.saved_status_title)) {
                assertNull("Water must not display status phase $phase", find { it.text?.contains(appText(id)) == true })
            }
            assertNull(find { it.text?.contains("FORBIDDEN_SUBJECT") == true })
            assertNull(find { it.text?.contains("FORBIDDEN_DETAIL") == true })
            assertNull(find { it.text?.contains(queryTime()) == true })
            awaitText(lastUsed(device))
        }
        capture("water-no-status")
    }

    @Test fun laundryListRejectsStatusForAnotherOwnerKindOrDevice() = withFixture { scenario ->
        val device = record(SavedDeviceKind.LAUNDRY)
        val valid = observation(device)
        val snapshot = mutableStateOf(valid.copy(owner = "other-owner"))
        mount(scenario) {
            SavedDevicesSheet(SavedDeviceKind.LAUNDRY, listOf(device), false, null,
                {}, {}, { _, _ -> }, {}, status = snapshot.value,
                statuses = mapOf(SavedDeviceStatus.key(device) to snapshot.value))
        }
        awaitText(device.name)
        for (mismatch in listOf(valid.copy(owner = "other-owner"),
            valid.copy(kind = SavedDeviceKind.WATER), valid.copy(deviceId = "other-device"))) {
            instrumentation.runOnMainSync { snapshot.value = mismatch }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            assertNull(find { it.text?.contains(appText(R.string.saved_status_list_available)) == true })
            assertNull(find { it.text?.contains(queryTime()) == true })
        }
        instrumentation.runOnMainSync { snapshot.value = valid }
        awaitText(appText(R.string.saved_status_list_available))
        awaitText(queryTime())
    }

    @Test fun detailedLaundryStatusCardRetainsItsFullObservation() = withFixture { scenario ->
        val status = observation(record(SavedDeviceKind.LAUNDRY))
        mount(scenario) { Column(Modifier.padding(16.dp)) { SavedDeviceStatusCard(status) } }
        awaitText(appText(R.string.saved_status_laundry_available))
        awaitText(appText(R.string.saved_status_mode, "FORBIDDEN_SUBJECT"))
        awaitText(appText(R.string.saved_status_remaining, 37))
        awaitText("FORBIDDEN_DETAIL")
        awaitText(queryTime())
        awaitText(appText(R.string.saved_status_snapshot_hint))
        assertNull(find { it.text?.contains(appText(R.string.saved_status_list_available)) == true })
    }

    private fun record(kind: SavedDeviceKind) = SavedDevice(owner, kind.wire, "fixture-${kind.wire}",
        "SN", "FIXTURE1234", if (kind == SavedDeviceKind.LAUNDRY) "离线洗衣机示例" else "离线饮水机示例",
        lastUsedAt = checkedAt - 60_000)

    private fun observation(device: SavedDevice) = SavedDeviceStatus(owner, requireNotNull(device.savedKind),
        device.deviceId, device.name, SavedDeviceStatusPhase.AVAILABLE, subject = "FORBIDDEN_SUBJECT",
        remainingMinutes = 37, detail = "FORBIDDEN_DETAIL", checkedAt = checkedAt)

    private fun queryTime() = appText(R.string.saved_status_checked_at,
        SimpleDateFormat("HH:mm:ss", AppStrings.locale()).format(Date(checkedAt)))

    private fun lastUsed(device: SavedDevice) = appText(R.string.saved_device_last_used,
        SimpleDateFormat("yyyy-MM-dd HH:mm", AppStrings.locale()).format(Date(device.lastUsedAt)))

    private fun mount(scenario: ActivityScenario<MainActivity>, content: @Composable () -> Unit) {
        scenario.onActivity { activity -> activity.setContent {
            LaundryTheme(ThemeSettings(splashEnabled = false)) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        } }
    }

    private fun withFixture(block: (ActivityScenario<MainActivity>) -> Unit) {
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
        fail("Timed out waiting for saved-device list presentation")
    }

    private fun awaitText(text: String) = await {
        find { it.text?.contains(text) == true } != null
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(180)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "device-status-r2")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
}
