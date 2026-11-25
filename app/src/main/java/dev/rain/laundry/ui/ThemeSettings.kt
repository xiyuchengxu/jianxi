package dev.rain.laundry.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rain.laundry.core.visibleNavigationTabs
import dev.rain.laundry.scanner.ScannerFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ThemeMode(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class DesignStyle { MATERIAL, MIUIX }
enum class ThemePalette(val title: String) {
    SLATE("雾蓝"), BLUE("海蓝"), TEAL("青碧"), GREEN("松绿"),
    PURPLE("鸢紫"), PINK("蔷薇"), ORANGE("暖橙"), GRAPHITE("石墨")
}
enum class ColorStyle(val title: String) { SOFT("柔和"), STANDARD("标准"), VIVID("鲜明") }
enum class BottomBarStyle(val title: String) { FLOATING("悬浮"), FIXED("固定") }
enum class PageAnimation(val title: String) { SLIDE("滑动"), FADE("淡入"), NONE("无动画") }
enum class GlassLevel(val title: String) { NONE("无"), LIGHT("轻度"), FULL("完全") }

/** Stable persisted names: never rename these enum constants without a migration. */
enum class AppFont { SYSTEM, YUNZHUAN_DUREN }

data class ThemeSettings(
    val designStyle: DesignStyle = DesignStyle.MATERIAL,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.SLATE,
    val colorStyle: ColorStyle = ColorStyle.STANDARD,
    val pureBlack: Boolean = false,
    val bottomBar: BottomBarStyle = BottomBarStyle.FLOATING,
    val animation: PageAnimation = PageAnimation.SLIDE,
    val dynamicColor: Boolean = false,
    val swipeNavigation: Boolean = true,
    val glassLevel: GlassLevel = GlassLevel.FULL,
    val font: AppFont = AppFont.SYSTEM,
    val laundryEnabled: Boolean = true,
    val dryerEnabled: Boolean = true,
    val waterEnabled: Boolean = true,
    val scannerEnabled: Boolean = true,
    val splashEnabled: Boolean = true,
    val splashImages: List<String> = emptyList(),
    val splashDurationMs: Int = DEFAULT_SPLASH_DURATION_MS,
    val homeMessage: String = DEFAULT_HOME_MESSAGE,
    val wallpaperBackgroundEnabled: Boolean = false,
    val wallpaperBackgroundBrightness: Float = 0f,
    val controlTransparency: Float? = null
) {
    val visibleTabs get() = visibleNavigationTabs(laundryEnabled, waterEnabled, dryerEnabled)
}

/** UI preferences are independent of the encrypted login/order store. */
class ThemePreferences(context: Context) {
    private companion object {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cleanupLock = Any()
        val sequenceLock = Any()
        var processCleanup: Job? = null
    }

    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val splashImageStore = SplashImageStore(context)
    var settings by mutableStateOf(ThemeSettings(
        designStyle = savedEnum("design_style", DesignStyle.MATERIAL),
        mode = savedEnum("mode", ThemeMode.SYSTEM),
        palette = savedEnum("palette", ThemePalette.SLATE),
        colorStyle = savedEnum("color_style", ColorStyle.STANDARD),
        pureBlack = preferences.getBoolean("pure_black", false),
        bottomBar = savedEnum("bottom_bar", BottomBarStyle.FLOATING),
        animation = savedEnum("animation", PageAnimation.SLIDE),
        dynamicColor = preferences.getBoolean("dynamic_color", false),
        wallpaperBackgroundEnabled = preferences.getBoolean("wallpaper_background_enabled", false),
        wallpaperBackgroundBrightness = normalizeWallpaperBrightness(runCatching {
            preferences.getFloat("wallpaper_background_brightness", 0f)
        }.getOrDefault(0f)),
        controlTransparency = normalizeControlTransparency(runCatching {
            preferences.getFloat("control_transparency", Float.NaN)
        }.getOrNull()),
        swipeNavigation = preferences.getBoolean("swipe_navigation", true),
        glassLevel = savedEnum("glass_level", GlassLevel.FULL),
        font = savedEnum("font", AppFont.SYSTEM),
        laundryEnabled = preferences.getBoolean("laundry_enabled", true),
        dryerEnabled = preferences.getBoolean("dryer_enabled", true),
        waterEnabled = preferences.getBoolean("water_enabled", true),
        scannerEnabled = ScannerFeature.isEnabled(context),
        splashEnabled = runCatching { preferences.getBoolean("splash_enabled", true) }.getOrDefault(true),
        splashImages = decodeSplashImages(runCatching { preferences.getString("splash_images", null) }.getOrNull()),
        splashDurationMs = savedSplashDurationMs(runCatching {
            preferences.getInt("splash_duration_ms", DEFAULT_SPLASH_DURATION_MS)
        }.getOrNull()),
        homeMessage = normalizeHomeMessage(runCatching { preferences.getString("home_message", null) }.getOrNull())
    ))
        private set

    private val initialCleanup = synchronized(cleanupLock) {
        processCleanup ?: cleanupScope.launch {
            try {
                val retained = decodeSplashImages(runCatching { preferences.getString("splash_images", null) }.getOrNull())
                splashImageStore.pruneUnused(retained)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Cleanup is best effort and must not prevent startup.
            }
        }.also { processCleanup = it }
    }

    fun update(value: ThemeSettings) {
        val previous = settings
        val previousImages = previous.splashImages
        settings = value.copy(
            wallpaperBackgroundBrightness = normalizeWallpaperBrightness(value.wallpaperBackgroundBrightness),
            controlTransparency = normalizeControlTransparency(value.controlTransparency),
            splashImages = normalizeSplashImageNames(value.splashImages),
            splashDurationMs = normalizeSplashDurationMs(value.splashDurationMs),
            homeMessage = normalizeHomeMessage(value.homeMessage)
        )
        // Slider drags only update changed preferences.
        if (previous != settings && previous.copy(wallpaperBackgroundBrightness = settings.wallpaperBackgroundBrightness,
                controlTransparency = settings.controlTransparency) == settings) {
            val editor = preferences.edit()
            if (previous.wallpaperBackgroundBrightness != settings.wallpaperBackgroundBrightness)
                editor.putFloat("wallpaper_background_brightness", settings.wallpaperBackgroundBrightness)
            if (previous.controlTransparency != settings.controlTransparency)
                editor.putControlTransparency(settings.controlTransparency)
            editor.apply()
            return
        }
        val editor = preferences.edit()
            .putString("design_style", value.designStyle.name)
            .putString("mode", value.mode.name)
            .putString("palette", value.palette.name)
            .putString("color_style", value.colorStyle.name)
            .putBoolean("pure_black", value.pureBlack)
            .putString("bottom_bar", value.bottomBar.name)
            .putString("animation", value.animation.name)
            .putBoolean("dynamic_color", value.dynamicColor)
            .putBoolean("wallpaper_background_enabled", value.wallpaperBackgroundEnabled)
            .putFloat("wallpaper_background_brightness", settings.wallpaperBackgroundBrightness)
            .putControlTransparency(settings.controlTransparency)
            .putBoolean("swipe_navigation", value.swipeNavigation)
            .putString("glass_level", value.glassLevel.name)
            .putString("font", value.font.name)
            .putBoolean("laundry_enabled", value.laundryEnabled)
            .putBoolean("dryer_enabled", value.dryerEnabled)
            .putBoolean("water_enabled", value.waterEnabled)
            .putBoolean(ScannerFeature.KEY_ENABLED, value.scannerEnabled)
            .putBoolean("splash_enabled", value.splashEnabled)
            .putString("splash_images", encodeSplashImages(settings.splashImages))
            .putInt("splash_duration_ms", settings.splashDurationMs)
            .putString("home_message", settings.homeMessage)
        if (previousImages != settings.splashImages) editor.putInt("splash_next_index", 0)
        editor.apply()
        if (previousImages != settings.splashImages) cleanupScope.launch {
            // An empty commit waits for preceding apply() writes, without overwriting newer settings.
            // Failed persistence keeps the old files available for whichever preference version survived.
            try {
                initialCleanup.join()
                if (preferences.edit().commit()) {
                    val retained = decodeSplashImages(runCatching { preferences.getString("splash_images", null) }.getOrNull())
                    splashImageStore.discardPrevious(previousImages, retained)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A later cold start can retry cleanup without risking the current images.
            }
        }
    }

    /** Call once when a new cold-start splash is created, never for recomposition or preview. */
    fun takeNextSplashImage(): String = synchronized(sequenceLock) {
        val images = effectiveSplashImages(settings.splashImages)
        val savedIndex = runCatching { preferences.getInt("splash_next_index", 0) }.getOrNull()
        val (current, next) = splashSequenceStep(savedIndex, images.size)
        preferences.edit().putInt("splash_next_index", next).apply()
        images[current]
    }

    private inline fun <reified T : Enum<T>> savedEnum(key: String, fallback: T): T =
        runCatching {
            enumValues<T>().firstOrNull { it.name == preferences.getString(key, null) }
        }.getOrNull() ?: fallback
}

private fun SharedPreferences.Editor.putControlTransparency(value: Float?): SharedPreferences.Editor =
    if (value == null) remove("control_transparency") else putFloat("control_transparency", value)
