package dev.rain.laundry

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.util.Xml
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.AgreementPolicy
import dev.rain.laundry.data.ConsentStore
import dev.rain.laundry.ui.SplashImageStore
import dev.rain.laundry.ui.ThemePreferences
import dev.rain.laundry.ui.ThemeSettings
import dev.rain.laundry.ui.rememberSplashBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Uses an isolated, logged-out emulator; no business actions or live API calls are required. */
@RunWith(AndroidJUnit4::class)
class SplashRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test fun oneImageAutomaticallyReturnsToTheAgreementGate() = withPreferences(accepted = false) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("开屏图片 1")
            assertImageOnlySplash()
            capture("splash-01-default-first")
            awaitText("免责声明与用户守则")
            assertNull(findText("手机号登录"))
            assertFalse(ConsentStore(context).isAccepted())
            scenario.recreate()
            awaitText("免责声明与用户守则")
            assertNull(findText("开屏图片 1"))
        }
    }

    @Test fun theNextColdLaunchUsesTheNextImageAndRecreationDoesNotConsumeOne() = withPreferences {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("开屏图片 1")
            assertImageOnlySplash()
            awaitText("手机号登录")
            scenario.recreate()
            awaitText("手机号登录")
            assertNull(findText("开屏图片 1"))
            assertNull(findText("开屏图片 2"))
            capture("splash-03-login-after-recreation")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText("开屏图片 2")
            assertImageOnlySplash()
            capture("splash-02-default-second")
            awaitText("手机号登录")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText("开屏图片 1")
        }
    }

    @Test fun durationDefaultsTo700MillisecondsAndDisabledSplashLaunchesDirectly() = withPreferences(enabled = false, durationMs = 700) {
        assertEquals(700, ThemeSettings().splashDurationMs)
        assertEquals(700, ThemePreferences(context).settings.splashDurationMs)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("手机号登录")
            assertNull(findText("开屏图片 1"))
            assertFalse(ThemePreferences(context).settings.splashEnabled)
            scenario.recreate()
            awaitText("手机号登录")
            assertNull(findText("开屏图片 1"))
        }
    }

    @Test fun importedImagesKeepTheirOrderAndLoadAfterTheOriginalFilesAreDeleted() = withPreferences { preferences ->
        val fixtures = File(context.cacheDir, "splash-runtime-${UUID.randomUUID()}").apply { mkdirs() }
        var imported = emptyList<String>()
        try {
            val first = fixture(fixtures, "first.png", Color.RED, 1000, 2500)
            val second = fixture(fixtures, "second.png", Color.BLUE, 120, 160)
            imported = runBlocking { SplashImageStore(context).importImages(listOf(Uri.fromFile(first), Uri.fromFile(second))) }
            assertEquals(2, imported.size)
            assertNotEquals(imported[0], imported[1])
            instrumentation.runOnMainSync { preferences.update(preferences.settings.copy(splashImages = imported)) }
            assertEquals(imported, ThemePreferences(context).settings.splashImages)
            assertTrue(first.delete())
            assertTrue(second.delete())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("自定义开屏图片")
                assertImageOnlySplash()
                awaitText("手机号登录")
                val firstBitmap = decodeInComposition(scenario, imported[0])
                assertTrue(firstBitmap.width <= 1920 && firstBitmap.height <= 1920)
                assertTrue(firstBitmap.height > firstBitmap.width)
                val firstPixel = firstBitmap.toPixelMap()[firstBitmap.width / 2, firstBitmap.height / 2]
                assertTrue(firstPixel.red > .9f && firstPixel.blue < .1f)
                val secondBitmap = decodeInComposition(scenario, imported[1])
                val secondPixel = secondBitmap.toPixelMap()[secondBitmap.width / 2, secondBitmap.height / 2]
                assertTrue(secondPixel.blue > .9f && secondPixel.red < .1f)
                // Missing private images must fall back to a bundled image instead of blocking startup.
                val fallback = decodeInComposition(scenario, "missing-image.jpg")
                assertTrue(fallback.width > 0 && fallback.height > 0)
            }
        } finally {
            imported.forEach { File(File(context.filesDir, "splash_images"), it).delete() }
            fixtures.listFiles()?.forEach { it.delete() }
            fixtures.delete()
        }
    }

    @Test fun themeDurationPreviewReorderAndRestorePreserveOtherSettings() = withPreferences(enabled = false) { preferences ->
        val fixtures = File(context.cacheDir, "splash-settings-${UUID.randomUUID()}").apply { mkdirs() }
        var imported = emptyList<String>()
        try {
            val first = fixture(fixtures, "first.png", Color.rgb(100, 155, 130), 240, 400)
            val second = fixture(fixtures, "second.png", Color.rgb(120, 140, 190), 240, 400)
            imported = runBlocking { SplashImageStore(context).importImages(listOf(Uri.fromFile(first), Uri.fromFile(second))) }
            instrumentation.runOnMainSync { preferences.update(preferences.settings.copy(splashImages = imported)) }
            val originalPalette = ThemePreferences(context).settings.palette
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("手机号登录")
                click("设置")
                click("主题设置")
                scrollToText("展开开屏设置")
                click("展开开屏设置")
                scrollToText("保存时长")
                setDurationText("50")
                click("保存时长")
                awaitText("请输入 100 至 60000 之间的整数（ms）")
                assertEquals(2500, ThemePreferences(context).settings.splashDurationMs)
                setDurationText("1800")
                click("保存时长")
                await { ThemePreferences(context).settings.splashDurationMs == 1800 }
                scrollToText("预览开屏")
                capture("splash-04-theme-settings")
                click("预览开屏")
                awaitText("自定义开屏图片")
                assertImageOnlySplash()
                capture("splash-05-theme-preview")
                awaitText("预览开屏")
                // Restore a known list anchor after the full-screen window changes its insets.
                scrollToText("显示开屏看板娘", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                scenario.onActivity { activity ->
                    val window = activity.window
                    File(File(context.getExternalFilesDir(null), "runtime-checks"), "splash-window.txt").writeText(
                        "activityFocus=${window.decorView.hasWindowFocus()}, attached=${window.decorView.isAttachedToWindow}, " +
                            "systemUi=${window.decorView.systemUiVisibility}, appearance=${window.insetsController?.systemBarsAppearance}"
                    )
                }
                scrollToText("第 1 张向后移动")
                click("第 1 张向后移动")
                await { ThemePreferences(context).settings.splashImages == imported.reversed() }
                scrollToText("添加图片")
                val beforePicker = ThemePreferences(context).settings
                click("添加图片")
                await { find(automation.rootInActiveWindow) { it.packageName?.toString() == "com.android.documentsui" } != null }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitText("添加图片")
                assertEquals("Cancelling the document picker must preserve current images", beforePicker, ThemePreferences(context).settings)
                scrollToText("显示开屏看板娘", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                await {
                    find(automation.rootInActiveWindow) {
                        it.isVisibleToUser && it.isClickable && it.contentDescription?.toString() == "显示开屏看板娘"
                    }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                await { ThemePreferences(context).settings.splashEnabled }
                assertEquals(originalPalette, ThemePreferences(context).settings.palette)
                scenario.recreate()
                awaitText("主题")
                assertEquals(imported.reversed(), ThemePreferences(context).settings.splashImages)
                assertEquals(1800, ThemePreferences(context).settings.splashDurationMs)
                assertNull(findText("自定义开屏图片"))
                scrollToText("恢复默认")
                click("恢复默认")
                await { ThemePreferences(context).settings.splashImages.isEmpty() }
                assertTrue(ThemePreferences(context).settings.splashEnabled)
                assertEquals(originalPalette, ThemePreferences(context).settings.palette)
                scrollToText("默认图片 · 2 张", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                capture("splash-06-theme-default-restored")
            }
        } finally {
            imported.forEach { File(File(context.filesDir, "splash_images"), it).delete() }
            fixtures.listFiles()?.forEach { it.delete() }
            fixtures.delete()
        }
    }

    @Test fun splashSettingsCollapseIndependentlyOfTheSwitchAndKeepEdits() = withPreferences(enabled = false, durationMs = 700) { preferences ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("手机号登录")
            click("设置")
            click("主题设置")
            scrollToText("展开开屏设置")
            assertNull(findText("保存时长"))
            assertNull(findText("预览开屏"))
            capture("splash-settings-collapsed")
            click("展开开屏设置")
            scrollToText("保存时长")
            assertFalse(preferences.settings.splashEnabled)
            setDurationText("1200")
            click("保存时长")
            await { ThemePreferences(context).settings.splashDurationMs == 1200 }
            scrollToText("收起开屏设置", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            click("收起开屏设置")
            await { findText("保存时长") == null }
            assertFalse(ThemePreferences(context).settings.splashEnabled)
            assertEquals(1200, ThemePreferences(context).settings.splashDurationMs)
            await {
                find(automation.rootInActiveWindow) {
                    it.isVisibleToUser && it.isClickable && it.contentDescription?.toString() == "显示开屏看板娘"
                }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await { ThemePreferences(context).settings.splashEnabled }
            assertNotNull(findText("展开开屏设置"))
            assertNull(findText("保存时长"))
            click("展开开屏设置")
            awaitText("收起开屏设置")
            captureNodeTree("splash-settings-reexpanded")
            scrollToText("保存时长")
            capture("splash-settings-expanded")
            scenario.recreate()
            awaitText("主题")
            scrollToText("收起开屏设置", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            assertNull(findText("展开开屏设置"))
            assertEquals(1200, ThemePreferences(context).settings.splashDurationMs)
        }
    }

    @Test fun anInvalidImageRollsBackTheWholeImportAndLeavesExistingSettingsIntact() = withPreferences { preferences ->
        val fixtures = File(context.cacheDir, "splash-invalid-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val valid = fixture(fixtures, "valid.png", Color.GREEN, 32, 48)
            val invalid = File(fixtures, "invalid.png").apply { writeText("This is not an image") }
            val original = preferences.settings
            val filesBefore = context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toSet()
            val result = runCatching {
                runBlocking { SplashImageStore(context).importImages(listOf(Uri.fromFile(valid), Uri.fromFile(invalid))) }
            }
            assertTrue(result.isFailure)
            assertEquals(original, ThemePreferences(context).settings)
            val filesAfter = context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toSet()
            assertEquals("A failed batch must not leave partially imported images", filesBefore, filesAfter)
        } finally {
            fixtures.listFiles()?.forEach { it.delete() }
            fixtures.delete()
        }
    }

    private fun decodeInComposition(scenario: ActivityScenario<MainActivity>, source: String): ImageBitmap {
        val result = AtomicReference<ImageBitmap?>()
        scenario.onActivity { activity ->
            activity.setContent {
                key(source) {
                    val bitmap by rememberSplashBitmap(source)
                    // Emit a visible node so Android continues drawing while asynchronous decode completes.
                    Text(if (bitmap == null) "正在解码测试图片" else "测试图片已解码")
                    SideEffect { result.set(bitmap) }
                }
            }
        }
        await { result.get() != null }
        return requireNotNull(result.get())
    }

    private fun fixture(directory: File, name: String, color: Int, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(directory, name).also { file ->
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }

    private fun withPreferences(enabled: Boolean = true, accepted: Boolean = true, durationMs: Int = 2500, block: (ThemePreferences) -> Unit) {
        val appearance = context.getSharedPreferences("appearance", 0)
        val previousAppearance = appearance.all.toMap()
        assertTrue(appearance.edit().clear().commit())
        val preferences = ThemePreferences(context)
        val original = preferences.settings
        assertEquals("A missing duration preference must use 700 ms", 700, original.splashDurationMs)
        val agreement = context.getSharedPreferences("usage_agreement", 0)
        val previousAgreement = agreement.all.toMap()
        assertTrue(agreement.edit().clear().commit())
        if (accepted) assertTrue(ConsentStore(context).accept(AgreementPolicy.CONFIRMATION))
        instrumentation.runOnMainSync {
            preferences.update(original.copy(splashEnabled = enabled, splashImages = emptyList(), splashDurationMs = durationMs))
        }
        try { block(preferences) }
        finally {
            instrumentation.runOnMainSync { preferences.update(original) }
            restore(agreement, previousAgreement)
            restore(appearance, previousAppearance)
        }
    }

    private fun assertImageOnlySplash() {
        assertNull(findText("跳过开屏"))
        assertNull(findText("进入简洗"))
        assertNull(findText("左右滑动，遇见下一张"))
        assertNull(find(automation.rootInActiveWindow) {
            it.isVisibleToUser && it.packageName?.toString() == context.packageName &&
                it.className?.toString() == "android.widget.Button"
        })
    }

    private fun setDurationText(value: String) {
        var editor: AccessibilityNodeInfo? = null
        await {
            editor = find(automation.rootInActiveWindow) { it.isVisibleToUser && it.isEditable }
            editor != null
        }
        assertTrue(requireNotNull(editor).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
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

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun findText(text: String) = find(automation.rootInActiveWindow) {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }

    private fun awaitText(text: String) = await { findText(text) != null }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        fail("Timed out waiting for splash UI")
    }

    private fun click(text: String) {
        await {
            var node = findText(text)
            while (node != null && !node.isClickable) node = node.parent
            // Previews restore system insets; bring the whole control into view, then reacquire its node.
            node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            SystemClock.sleep(140)
            node = findText(text)
            while (node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(320)
    }

    private fun scrollToText(text: String, direction: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        if (text.contains("向后移动")) captureNodeTree("splash-before-sort")
        await {
            findText(text) != null || verticalScrollContainer() != null
        }
        repeat(42) {
            if (findText(text) != null) return
            val scroll = verticalScrollContainer()
            assertNotNull("No scroll container while looking for $text", scroll)
            scroll?.let { scrollBySmallGesture(it, direction) }
            SystemClock.sleep(180)
        }
        if (findText(text) == null) {
            captureNodeTree("splash-failure")
            SystemClock.sleep(10_000)
            fail("Could not reveal $text; captured accessibility tree and live screenshot")
        }
    }

    private fun captureNodeTree(name: String) {
        capture(name)
        val directory = File(context.getExternalFilesDir(null), "runtime-checks").apply { mkdirs() }
        File(directory, "$name-tree.xml").outputStream().use { output ->
            val serializer = Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true) }
            serializer.startTag(null, "hierarchy")
            serializer.attribute(null, "windows", automation.windows.joinToString { "${it.title}:active=${it.isActive}:focused=${it.isFocused}" })
            fun writeNode(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val bounds = Rect().also(node::getBoundsInScreen)
                serializer.startTag(null, "node")
                for ((key, value) in mapOf("text" to node.text, "content-desc" to node.contentDescription,
                    "class" to node.className, "visible" to node.isVisibleToUser, "clickable" to node.isClickable,
                    "enabled" to node.isEnabled, "bounds" to bounds.toShortString())) {
                    serializer.attribute(null, key, value?.toString().orEmpty())
                }
                for (index in 0 until node.childCount) writeNode(node.getChild(index))
                serializer.endTag(null, "node")
            }
            writeNode(automation.rootInActiveWindow)
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
        }
    }

    private fun verticalScrollContainer(): AccessibilityNodeInfo? {
        var largest: AccessibilityNodeInfo? = null
        var largestArea = 0L
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && node.isScrollable && node.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val area = bounds.width().toLong() * bounds.height()
                if (area > largestArea) { largest = node; largestArea = area }
            }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(automation.rootInActiveWindow)
        return largest
    }

    private fun scrollBySmallGesture(node: AccessibilityNodeInfo, direction: Int) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val distance = minOf(260f, bounds.height() * .18f)
        val startY = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            bounds.top + bounds.height() * .7f else bounds.top + bounds.height() * .3f
        val endY = startY + if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) -distance else distance
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            // Use the list gutter so text fields and interactive previews cannot consume the drag.
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.left + 24f, y, 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startY)
        for (step in 1..6) {
            SystemClock.sleep(25)
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 6)
        }
        SystemClock.sleep(180)
        send(MotionEvent.ACTION_UP, endY)
    }


    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(automation.takeScreenshot())
        val screenshots = File(context.getExternalFilesDir(null), "runtime-checks").apply { mkdirs() }
        try { File(screenshots, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
