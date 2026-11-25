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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.PointsTask
import dev.rain.laundry.core.PointsUiState
import dev.rain.laundry.i18n.AppLanguage
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.LanguagePreferences
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.ui.DesignStyle
import dev.rain.laundry.ui.LaundryTheme
import dev.rain.laundry.ui.PointsScreen
import dev.rain.laundry.ui.ThemeMode
import dev.rain.laundry.ui.ThemeSettings
import dev.rain.laundry.ui.laundryColorScheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Offline checks of the actual points component. The consent gate prevents API/model creation. */
@RunWith(AndroidJUnit4::class)
class PointsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun unknownBalanceAndUnloadedTasksStayUnknown() = withGate { scenario ->
        mount(scenario, PointsUiState())
        awaitText("—")
        awaitText(appText(R.string.points_sign_status_unknown))
        scrollToText(appText(R.string.points_not_loaded))
        assertNull(find { it.text?.toString() == "0" })
        assertNull(find { it.text?.toString() == appText(R.string.points_already_signed) })
        assertNull(find { it.text?.toString() == appText(R.string.points_task_done) })
        capture("unknown-balance.png")
    }

    @Test fun signedStatusAndPlatformTaskContentAreShownVerbatim() = withGate { scenario ->
        val task = PointsTask(id = "platform-task", name = "平台原文 · Task_日本語", reward = "8",
            completed = 2, limit = 3, description = "平台说明 · Keep this text")
        val completedTask = PointsTask(id = "finished-task", name = "平台原文 · Finished", done = true)
        mount(scenario, PointsUiState(loaded = true, balance = "120", signedInToday = true,
            tasks = listOf(task, completedTask)))
        awaitText("120")
        awaitText(appText(R.string.points_already_signed))
        scrollToText(task.name)
        scrollToText(appText(R.string.points_task_reward, "8"))
        scrollToText(appText(R.string.points_task_progress, 2, 3))
        scrollToText(task.description)
        scrollToText(appText(R.string.points_task_original))
        scrollToText(completedTask.name)
        scrollToText(appText(R.string.points_task_done))
        capture("platform-task-status.png")
    }

    @Test fun eachActionCallsOnlyItsOwnCallback() = withGate { scenario ->
        val refreshes = AtomicInteger()
        val starts = AtomicInteger()
        val opens = AtomicInteger()
        mount(scenario, PointsUiState(loaded = true, balance = "30", signedInToday = false),
            onRefresh = { refreshes.incrementAndGet() }, onStart = { starts.incrementAndGet() },
            onOpenOriginal = { opens.incrementAndGet() })
        click(appText(R.string.points_start))
        await { starts.get() == 1 }
        assertEquals(0, refreshes.get())
        assertEquals(0, opens.get())
        click(appText(R.string.points_refresh))
        await { refreshes.get() == 1 }
        assertEquals(1, starts.get())
        assertEquals(0, opens.get())
        click(appText(R.string.points_open_original))
        await { opens.get() == 1 }
        assertEquals(1, starts.get())
        assertEquals(1, refreshes.get())
    }

    @Test fun loadingAndRunningPreventDuplicateActions() = withGate { scenario ->
        val calls = AtomicInteger()
        val busyStates = listOf(
            PointsUiState(loading = true, progress = appText(R.string.points_loading)),
            PointsUiState(running = true, progress = appText(R.string.points_signing))
        )
        busyStates.forEachIndexed { index, state ->
            mount(scenario, state, onRefresh = { calls.incrementAndGet() },
                onStart = { calls.incrementAndGet() }, onOpenOriginal = { calls.incrementAndGet() })
            awaitText(state.progress)
            for (label in listOf(R.string.points_start, R.string.points_refresh)) {
                val action = actionFor(scrollToText(appText(label)))
                assertNotNull("The points button must retain its accessibility node", action)
                assertFalse("Duplicate actions must be disabled", requireNotNull(action).isEnabled)
                action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, calls.get())
            capture(if (index == 0) "loading-disabled.png" else "running-disabled.png")
        }
    }

    @Test fun loggedOutStateExposesNoTaskActionsOrAccountHistory() = withGate { scenario ->
        val calls = AtomicInteger()
        mount(scenario, PointsUiState(), loggedIn = false,
            onRefresh = { calls.incrementAndGet() }, onStart = { calls.incrementAndGet() },
            onOpenOriginal = { calls.incrementAndGet() })
        awaitText(appText(R.string.points_login_required))
        for (label in listOf(R.string.points_start, R.string.points_refresh,
            R.string.points_open_original, R.string.points_tasks_title, R.string.points_logs_title)) {
            assertNull(find { it.text?.toString() == appText(label) })
        }
        assertNull(find { it.isClickable && it.isEnabled })
        assertEquals(0, calls.get())
        capture("logged-out.png")
    }

    @Test fun materialAndMiuixSupportLightAndDarkLayouts() = withGate { scenario ->
        val state = PointsUiState(loaded = true, balance = "168", signedInToday = true,
            tasks = listOf(PointsTask(id = "layout-task", name = "平台积分任务", completed = 1,
                limit = 2, description = "平台返回的任务说明")))
        for (style in listOf(DesignStyle.MATERIAL, DesignStyle.MIUIX)) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val settings = ThemeSettings(splashEnabled = false, dynamicColor = false,
                    designStyle = style, mode = mode)
                mount(scenario, state, settings = settings)
                val expectedBackground = laundryColorScheme(settings, mode == ThemeMode.DARK).background.toArgb()
                // Identical text can remain accessible while the previous theme frame is on screen.
                // Wait for the actual Surface color, outside every padded child, before recording it.
                await {
                    val bitmap = automation.takeScreenshot() ?: return@await false
                    try {
                        bitmap.getPixel(5, bitmap.height / 2) == expectedBackground
                    } finally {
                        bitmap.recycle()
                    }
                }
                assertVisibleBounds(awaitText("168"))
                assertVisibleBounds(awaitText(appText(R.string.points_start)))
                assertVisibleBounds(scrollToText("平台积分任务"))
                assertVisibleBounds(scrollToText(appText(R.string.points_task_progress, 1, 2)))
                capture("${style.name.lowercase()}-${mode.name.lowercase()}.png")
            }
        }
    }

    private fun mount(
        scenario: ActivityScenario<MainActivity>,
        state: PointsUiState,
        loggedIn: Boolean = true,
        settings: ThemeSettings = ThemeSettings(splashEnabled = false),
        onRefresh: () -> Unit = {},
        onStart: () -> Unit = {},
        onOpenOriginal: () -> Unit = {}
    ) {
        scenario.onActivity { activity -> activity.setContent {
            key(state, loggedIn, settings) {
                LaundryTheme(settings) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PointsScreen(state, loggedIn, true, onRefresh, onStart, onOpenOriginal)
                        }
                    }
                }
            }
        } }
        instrumentation.waitForIdleSync()
    }

    private fun assertVisibleBounds(node: AccessibilityNodeInfo) {
        assertTrue("Expected a visible points element", node.isVisibleToUser)
        val bounds = Rect().also(node::getBoundsInScreen)
        val metrics = context.resources.displayMetrics
        assertFalse("Points element has no visible bounds", bounds.isEmpty)
        assertTrue("Points element is outside the screen", bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= metrics.widthPixels && bounds.bottom <= metrics.heightPixels)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "points-runtime").apply { mkdirs() }
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
        values.forEach { (name, value) -> when (value) {
            is String -> editor.putString(name, value)
            is Boolean -> editor.putBoolean(name, value)
            is Long -> editor.putLong(name, value)
            is Int -> editor.putInt(name, value)
            is Float -> editor.putFloat(name, value)
            is Set<*> -> editor.putStringSet(name, value.filterIsInstance<String>().toSet())
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
        fail("Timed out waiting for points presentation")
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = find { it.text?.toString() == text }; result != null }
        return requireNotNull(result)
    }

    private fun scrollToText(text: String): AccessibilityNodeInfo {
        repeat(12) {
            find { it.isVisibleToUser && it.text?.toString() == text }?.let { return it }
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        fail("Points text not visible after scrolling: $text")
        throw AssertionError(text)
    }

    private fun actionFor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var action = node
        while (action != null && !action.isClickable) action = action.parent
        return action
    }

    private fun click(text: String) {
        val action = actionFor(scrollToText(text))
        assertTrue("Expected an enabled points action: $text", action?.isEnabled == true)
        assertTrue(requireNotNull(action).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
