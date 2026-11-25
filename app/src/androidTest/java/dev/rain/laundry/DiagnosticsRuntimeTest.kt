package dev.rain.laundry

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import dev.rain.laundry.core.AgreementPolicy
import dev.rain.laundry.data.ConsentStore
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.diagnostics.DiagnosticOperation
import dev.rain.laundry.diagnostics.DiagnosticStatus
import dev.rain.laundry.diagnostics.DiagnosticsLog
import dev.rain.laundry.diagnostics.DiagnosticCodec
import dev.rain.laundry.diagnostics.DiagnosticEntry
import dev.rain.laundry.diagnostics.DiagnosticsExportResult
import dev.rain.laundry.diagnostics.DiagnosticsExportViewModel
import dev.rain.laundry.i18n.AppLanguage
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.LanguagePreferences
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.ui.DesignStyle
import dev.rain.laundry.ui.ThemePreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Real MainActivity navigation, using only an already logged-out, dedicated test install. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun loggedOutUserCanOpenDiagnosticsThroughSettingsWithoutPoints() = withFixture(DesignStyle.MATERIAL) {
        openDiagnosticsThroughSettings()
        awaitNode { it.text?.toString() == appText(R.string.diagnostics_summary, DiagnosticsLog.MAX_ENTRIES) }
        assertNotNull(find { it.text?.toString() == appText(R.string.diagnostics_export) })
        assertNoPoints()
        capture("material-diagnostics.png")
        click(appText(R.string.scanner_close))
        awaitNode { it.contentDescription?.toString() == appText(R.string.diagnostics_title) }
        assertNoPoints()
    }

    @Test fun syntheticResultsAreVisibleAndClearRequiresConfirmation() = withFixture(DesignStyle.MATERIAL) {
        openDiagnosticsThroughSettings()
        seedResult()
        assertTextVisible(appText(R.string.diagnostics_op_water_query))
        assertTextVisible(appText(R.string.diagnostics_status_http_error))
        assertTextVisible(appText(R.string.diagnostics_duration, 87L))
        assertTextVisible(appText(R.string.diagnostics_error_code, 405))
        val before = DiagnosticsLog.entries.value
        scrollToTop()
        click(appText(R.string.diagnostics_clear))
        awaitNode { it.text?.toString() == appText(R.string.diagnostics_clear_confirm_title) }
        assertEquals(before, DiagnosticsLog.entries.value)
        capture("material-clear-confirmation.png")
        click(appText(R.string.diagnostics_clear_cancel))
        await { find { it.text?.toString() == appText(R.string.diagnostics_clear_confirm_title) } == null }
        assertEquals(before, DiagnosticsLog.entries.value)
        click(appText(R.string.diagnostics_clear))
        click(appText(R.string.diagnostics_clear_confirm))
        awaitNode { it.text?.toString() == appText(R.string.diagnostics_empty) }
        assertTrue(DiagnosticsLog.entries.value.isEmpty())
        assertFalse(File(context.noBackupFilesDir, "local-diagnostics-v1.txt").exists())
        capture("material-diagnostics-cleared.png")
    }

    @Test fun miuixDiagnosticsRetainsVisibleResultsAndClearCancelBehavior() = withFixture(DesignStyle.MIUIX) {
        assertEquals(DesignStyle.MIUIX, ThemePreferences(context).settings.designStyle)
        openDiagnosticsThroughSettings()
        seedResult()
        assertTextVisible(appText(R.string.diagnostics_error_code, 405))
        assertNoPoints()
        capture("miuix-diagnostics.png")
        val before = DiagnosticsLog.entries.value
        scrollToTop()
        click(appText(R.string.diagnostics_clear))
        awaitNode { it.text?.toString() == appText(R.string.diagnostics_clear_confirm_body) }
        click(appText(R.string.diagnostics_clear_cancel))
        await { find { it.text?.toString() == appText(R.string.diagnostics_clear_confirm_title) } == null }
        assertEquals(before, DiagnosticsLog.entries.value)
        click(appText(R.string.diagnostics_clear))
        click(appText(R.string.diagnostics_clear_confirm))
        awaitNode { it.text?.toString() == appText(R.string.diagnostics_empty) }
        assertTrue(DiagnosticsLog.entries.value.isEmpty())
        capture("miuix-diagnostics-cleared.png")
    }

    @Test fun txtExportWritesExactContentAndRetainsResultAcrossRecreation() = withFixture(DesignStyle.MATERIAL) { scenario ->
        openDiagnosticsThroughSettings()
        val target = File.createTempFile("diagnostics-export-test-", ".txt", context.cacheDir)
        val expected = DiagnosticCodec.export(listOf(DiagnosticEntry(1_700_000_000_000,
            DiagnosticOperation.WATER_QUERY, DiagnosticStatus.HTTP_ERROR, 87, 405)))
        val originalModel = AtomicReference<DiagnosticsExportViewModel>()
        try {
            // A shorter export must truncate the previous file rather than leave stale trailing data.
            target.writeText("obsolete data ".repeat(200), Charsets.UTF_8)
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[DiagnosticsExportViewModel::class.java]
                originalModel.set(model)
                model.export(Uri.fromFile(target), expected)
                assertTrue(model.state.value.writing)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(originalModel.get(), ViewModelProvider(activity)[DiagnosticsExportViewModel::class.java])
            }
            await { originalModel.get().state.value.result != null }
            assertFalse(originalModel.get().state.value.writing)
            assertEquals(DiagnosticsExportResult.SAVED, originalModel.get().state.value.result)
            assertEquals(expected, target.readText(Charsets.UTF_8))
            assertTrue(target.readText(Charsets.UTF_8).contains("WATER_QUERY\tHTTP_ERROR\t87\t405"))
            awaitNode { it.text?.toString() == appText(R.string.diagnostics_export_done) }
            val exportButton = awaitNode { it.text?.toString() == appText(R.string.diagnostics_export) }
            var action: AccessibilityNodeInfo? = exportButton
            while (action != null && !action.isClickable) action = action.parent
            assertTrue(requireNotNull(action).isEnabled)
        } finally {
            target.delete()
        }
    }

    private fun seedResult() {
        DiagnosticsLog.clear()
        // The foreground entry can already have a count of one before the fixture is rendered.
        awaitNode { it.text?.toString() == appText(R.string.diagnostics_empty) }
        DiagnosticsLog.record(DiagnosticOperation.WATER_QUERY, DiagnosticStatus.HTTP_ERROR, 87, 405)
        await { DiagnosticsLog.entries.value.size == 1 }
        await { find { it.text?.toString() == appText(R.string.diagnostics_count, 1) } != null }
    }

    private fun openDiagnosticsThroughSettings() {
        click(appText(R.string.msg_df3d58c7d84b))
        awaitNode { it.contentDescription?.toString() == appText(R.string.diagnostics_title) }
        assertNoPoints()
        click(appText(R.string.diagnostics_title))
        awaitNode { it.text?.toString() == appText(R.string.diagnostics_export) }
    }

    private fun assertNoPoints() {
        assertNull(find { node ->
            node.text?.contains("积分") == true || node.contentDescription?.contains("积分") == true
        })
    }

    private fun withFixture(style: DesignStyle, test: (ActivityScenario<MainActivity>) -> Unit) {
        // Never log out or overwrite an actual account in order to run this presentation test.
        assumeTrue("Requires a dedicated logged-out test install", SessionVault(context).token().isBlank())
        val preferences = listOf("usage_agreement", "appearance", "app_language")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val original = preferences.mapValues { HashMap(it.value.all) }
        val oldLocales = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else null
        // Preserve existing diagnostic entries and their exact timestamps without adding production hooks.
        val originalEntries = DiagnosticsLog.entries.value.toList()
        val diagnosticFile = File(context.noBackupFilesDir, "local-diagnostics-v1.txt")
        val originalFile = diagnosticFile.takeIf { it.isFile }?.readBytes()
        try {
            assertTrue(preferences.getValue("appearance").edit().putBoolean("splash_enabled", false)
                .putString("design_style", style.name).commit())
            assertTrue(ConsentStore(context).accept(AgreementPolicy.CONFIRMATION))
            instrumentation.runOnMainSync { LanguagePreferences.set(context, AppLanguage.SIMPLIFIED_CHINESE) }
            AppStrings.invalidate()
            ActivityScenario.launch(MainActivity::class.java).use(test)
        } finally {
            restoreDiagnostics(originalEntries, originalFile)
            if (Build.VERSION.SDK_INT >= 33 && oldLocales != null) instrumentation.runOnMainSync {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(oldLocales)
            }
            original.forEach { (name, values) -> restorePreferences(preferences.getValue(name), values) }
            AppStrings.invalidate()
        }
    }

    /** Fixture-only reflection keeps the production API restricted to fixed-schema recording. */
    private fun restoreDiagnostics(entries: List<dev.rain.laundry.diagnostics.DiagnosticEntry>, fileBytes: ByteArray?) {
        DiagnosticsLog.clear() // Invalidates pending asynchronous fixture writes before restoration.
        val field = DiagnosticsLog::class.java.getDeclaredField("mutableEntries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(DiagnosticsLog) as kotlinx.coroutines.flow.MutableStateFlow<List<dev.rain.laundry.diagnostics.DiagnosticEntry>>
        state.value = entries
        if (fileBytes != null) File(context.noBackupFilesDir, "local-diagnostics-v1.txt").writeBytes(fileBytes)
    }

    private fun restorePreferences(preferences: SharedPreferences, values: Map<String, *>) {
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

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "diagnostics-runtime").apply { mkdirs() }
        val bitmap = requireNotNull(automation.takeScreenshot())
        FileOutputStream(File(directory, name)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
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
        fail("Timed out waiting for diagnostics UI")
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = find { it.isVisibleToUser && predicate(it) }; result != null }
        return requireNotNull(result)
    }

    private fun assertTextVisible(label: String) {
        repeat(8) {
            if (find { it.isVisibleToUser && it.text?.toString() == label } != null) return
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        fail("Diagnostic result was not visible: $label")
    }

    private fun scrollToTop() {
        repeat(8) {
            val scrolled = find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            if (!scrolled) return
            SystemClock.sleep(120)
        }
    }

    private fun click(label: String) {
        await {
            var node = find { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
            while (node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
}
