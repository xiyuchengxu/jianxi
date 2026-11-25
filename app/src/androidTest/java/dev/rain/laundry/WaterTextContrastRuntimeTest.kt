package dev.rain.laundry

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.WaterDevice
import dev.rain.laundry.core.WaterOutlet
import dev.rain.laundry.core.WaterPayRoute
import dev.rain.laundry.core.WaterStatus
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Pixel checks deliberately use the app Scaffold without a Material Surface masking inherited text colors. */
@RunWith(AndroidJUnit4::class)
class WaterTextContrastRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private data class Fixture(val revision: Int, val settings: ThemeSettings, val device: Boolean)
    private data class Expected(val revision: Int, val text: Int, val background: Int, val primary: Int)
    private data class Marker(val revision: Int, val bounds: Rect)

    @Test fun waterBareTitlesStayReadableWhenThemeChanges() = withGate { scenario ->
        val update = AtomicReference<(Fixture) -> Unit>()
        val rendered = AtomicReference<Expected>()
        val marker = AtomicReference<Marker>()
        val outlet = WaterOutlet("test-outlet", "Test outlet", "0.10 / L")
        val device = WaterDevice("test-device", "Contrast test device", "test-imei", "test-shop", "test-org",
            listOf(outlet), true)
        val selected = UiState(waterDevice = device, waterOutlet = outlet, waterStatus = WaterStatus(1, 1),
            waterPayments = listOf(WaterPayRoute.BALANCE), waterPayment = WaterPayRoute.BALANCE)
        scenario.onActivity { activity -> activity.setContent {
            var fixture by remember { mutableStateOf(Fixture(0, ThemeSettings(splashEnabled = false), false)) }
            val view = LocalView.current
            SideEffect { update.set { fixture = it } }
            LaundryTheme(fixture.settings) {
                val colors = MaterialTheme.colorScheme
                val wallpaper = useSystemWallpaperWindow(fixture.settings.wallpaperBackgroundEnabled)
                SideEffect { rendered.set(Expected(fixture.revision, colors.onBackground.toArgb(),
                    colors.background.toArgb(), colors.primary.toArgb())) }
                val tint = if (wallpaper) wallpaperBrightnessOverlay(fixture.settings.wallpaperBackgroundBrightness) else ComposeColor.Transparent
                AppScaffold(modifier = Modifier.background(tint),
                    containerColor = if (wallpaper) ComposeColor.Transparent else colors.background) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            WaterScreen(if (fixture.device) selected else UiState(), {}, {}, {}, {}, {}, {}, {})
                        }
                        // A frame signature in the unused right gutter synchronizes pixels independently of text colors.
                        key(fixture.revision) {
                            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(4.dp)
                                .background(ComposeColor(markerColor(fixture.revision)))
                                .onGloballyPositioned { coordinates ->
                                    val inWindow = IntArray(2).also(view::getLocationInWindow)
                                    val onScreen = IntArray(2).also(view::getLocationOnScreen)
                                    val bounds = coordinates.boundsInWindow()
                                    val dx = onScreen[0] - inWindow[0]
                                    val dy = onScreen[1] - inWindow[1]
                                    marker.set(Marker(fixture.revision, Rect((bounds.left + dx).roundToInt(),
                                        (bounds.top + dy).roundToInt(), (bounds.right + dx).roundToInt(),
                                        (bounds.bottom + dy).roundToInt())))
                                })
                        }
                    }
                }
            }
        } }
        await { update.get() != null }
        val output = File(context.getExternalFilesDir(null), "water-text-contrast").apply { mkdirs() }
        File(output, "pixels.txt").writeText("")
        var revision = 0
        for (style in listOf(DesignStyle.MATERIAL, DesignStyle.MIUIX)) {
            val base = ThemeSettings(designStyle = style, dynamicColor = false, splashEnabled = false)
            val settings = mutableListOf(base.copy(mode = ThemeMode.LIGHT), base.copy(mode = ThemeMode.DARK),
                base.copy(mode = ThemeMode.DARK, pureBlack = true))
            if (context.getSystemService(android.app.WallpaperManager::class.java)?.isWallpaperSupported == true) {
                val whiteWallpaper = base.copy(mode = ThemeMode.DARK, wallpaperBackgroundEnabled = true,
                    wallpaperBackgroundBrightness = 1f)
                settings.add(whiteWallpaper.copy(controlTransparency = 0f)) // Original solid text backing.
                settings.add(whiteWallpaper) // Default wallpaper controls.
                settings.add(whiteWallpaper.copy(controlTransparency = 1f))
            }
            for (theme in settings) {
                for (hasDevice in listOf(false, true)) {
                    val fixture = Fixture(++revision, theme, hasDevice)
                    scenario.onActivity { update.get()(fixture) }
                    await { rendered.get()?.revision == fixture.revision }
                    val label = appText(if (hasDevice) R.string.msg_ca7223717fa6 else R.string.msg_88ef220dbc2f)
                    val title = awaitText(label)
                    val primary = awaitText(appText(R.string.msg_0e5d45e9456f))
                    instrumentation.waitForIdleSync()
                    await { marker.get()?.revision == fixture.revision }
                    val expected = rendered.get()
                    val bitmap = awaitMarkedScreenshot(marker.get().bounds, markerColor(fixture.revision))
                    val transparency = theme.effectiveControlTransparency(theme.wallpaperBackgroundEnabled)
                    val backdrop = when {
                        theme.wallpaperBackgroundEnabled -> "WHITE-WALLPAPER-${(100 * transparency).roundToInt()}"
                        theme.pureBlack -> "BLACK"
                        else -> theme.mode.name
                    }
                    val name = "${style.name}-$backdrop-${if (hasDevice) "payment" else "empty"}"
                    try {
                        FileOutputStream(File(output, "$name.png")).use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        if (theme.pureBlack) assertEquals("Pure-black fixture must use black background", Color.BLACK, expected.background)
                        val background = if (theme.wallpaperBackgroundEnabled)
                            ColorUtils.blendARGB(expected.background, Color.WHITE, transparency) else expected.background
                        val outline = if (theme.wallpaperBackgroundEnabled && transparency > 0f) Color.BLACK else null
                        if (theme.wallpaperBackgroundEnabled) {
                            // Sample the unused left gutter and assess text contrast against its actual local backing.
                            assertTrue("Expected a white wallpaper behind the dark theme",
                                distance(bitmap.getPixel(2, bitmap.height / 2), Color.WHITE) <= 4)
                        }
                        assertPixels(bitmap, title, expected.text, background, 4.5, name, output, outline)
                        // The accent reminder is explicit primary text and must retain that color.
                        assertPixels(bitmap, primary, expected.primary, background,
                            if (theme.wallpaperBackgroundEnabled) 4.5 else 0.0, "$name-primary", output, outline)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private fun markerColor(revision: Int): Int = Color.rgb(20 + (revision * 37 + 41) % 200,
        20 + (revision * 67 + 53) % 200, 20 + (revision * 97 + 71) % 200)

    private fun awaitMarkedScreenshot(bounds: Rect, expectedColor: Int): Bitmap {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            val bitmap = automation.takeScreenshot()
            if (bitmap != null) {
                val x = bounds.centerX()
                val y = bounds.centerY()
                if (x in 0 until bitmap.width && y in 0 until bitmap.height &&
                    distance(bitmap.getPixel(x, y), expectedColor) <= 3) return bitmap
                bitmap.recycle()
            }
            SystemClock.sleep(40)
        }
        throw AssertionError("Screenshot did not reach the fixture revision marker")
    }

    private fun assertPixels(bitmap: Bitmap, bounds: Rect, foreground: Int, background: Int,
        minimumContrast: Double, name: String, output: File, outline: Int? = null) {
        val area = Rect(bounds).apply { intersect(0, 0, bitmap.width, bitmap.height) }
        assertTrue("$name text has no visible bounds", area.width() > 0 && area.height() > 0)
        val counts = HashMap<Int, Int>()
        var foregroundCount = 0
        var outlinedCount = 0
        val reach = (context.resources.displayMetrics.density * 1.5f).roundToInt().coerceAtLeast(1)
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val pixel = bitmap.getPixel(x, y) or (0xff shl 24)
            counts[pixel] = (counts[pixel] ?: 0) + 1
            if (distance(pixel, foreground) > 5) continue
            foregroundCount++
            if (outline != null && hasContrastingEdge(bitmap, area, x, y, reach, pixel, outline, minimumContrast))
                outlinedCount++
        }
        val backingPixels = counts.filterKeys { distance(it, background) <= 12 }
        // At full transparency a dense outlined glyph may contain more ink than exposed wallpaper.
        val observedBackground = if (outline != null && backingPixels.isNotEmpty())
            backingPixels.maxBy { it.value }.key else counts.maxBy { it.value }.key
        val observedForeground = counts.keys.minByOrNull { distance(it, foreground) }!!
        val contrast = ColorUtils.calculateContrast(observedForeground, observedBackground)
        File(output, "pixels.txt").appendText("$name foreground=${Integer.toHexString(observedForeground)} " +
            "background=${Integer.toHexString(observedBackground)} matching=$foregroundCount " +
            "foregroundDistance=${distance(observedForeground, foreground)} contrast=$contrast outlined=$outlinedCount\n")
        assertTrue("$name actual backing differs from expected transparency", distance(observedBackground, background) <= 12)
        assertTrue("$name has too little of the expected backing visible",
            backingPixels.values.sum() >= max(8, area.width() * area.height() / 12))
        assertTrue("$name expected text color is absent from actual screenshot ($foregroundCount pixels)",
            foregroundCount >= max(5, area.width() * area.height() / 500))
        if (outline == null) {
            assertTrue("$name actual text/background contrast $contrast is too low", contrast >= minimumContrast)
        } else {
            assertTrue("$name needs readable contours beside the actual glyphs, found $outlinedCount",
                outlinedCount >= max(8, foregroundCount / 12))
        }
    }

    /** Look across a glyph's antialiased edge for outline ink within 1.5dp of its solid fill. */
    private fun hasContrastingEdge(bitmap: Bitmap, area: Rect, x: Int, y: Int, reach: Int,
        foreground: Int, outline: Int, minimumContrast: Double): Boolean {
        fun matches(px: Int, py: Int): Boolean {
            if (!area.contains(px, py)) return false
            val ink = bitmap.getPixel(px, py)
            return distance(ink, outline) <= 20 && ColorUtils.calculateContrast(foreground, ink) >= minimumContrast
        }
        for (step in 1..reach)
            if (matches(x + step, y) || matches(x - step, y) || matches(x, y + step) || matches(x, y - step)) return true
        return false
    }

    private fun distance(a: Int, b: Int): Int = max(abs(Color.red(a) - Color.red(b)),
        max(abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b))))

    private fun awaitText(text: String): Rect {
        var bounds: Rect? = null
        await {
            fun visit(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isVisibleToUser && node.text?.toString() == text) {
                    bounds = Rect().also(node::getBoundsInScreen); return true
                }
                for (i in 0 until node.childCount) if (visit(node.getChild(i))) return true
                return false
            }
            visit(automation.rootInActiveWindow)
        }
        return requireNotNull(bounds)
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        fail("Timed out waiting for water text contrast fixture")
    }

    private fun withGate(block: (ActivityScenario<MainActivity>) -> Unit) {
        val preferences = listOf("usage_agreement", "appearance", "app_language")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val originals = preferences.mapValues { HashMap(it.value.all) }
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
            originals.forEach { (name, values) -> restore(preferences.getValue(name), values) }
            AppStrings.invalidate()
        }
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val edit = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Long -> edit.putLong(key, value)
            is Int -> edit.putInt(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(edit.commit())
    }
}
