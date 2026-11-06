package dev.rain.laundry.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rain.laundry.core.visibleNavigationTabs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ThemeMode(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class ThemePalette(val title: String) {
    SLATE("雾蓝"), BLUE("海蓝"), TEAL("青碧"), GREEN("松绿"),
    PURPLE("鸢紫"), PINK("蔷薇"), ORANGE("暖橙"), GRAPHITE("石墨")
}
enum class ColorStyle(val title: String) { SOFT("柔和"), STANDARD("标准"), VIVID("鲜明") }
enum class BottomBarStyle(val title: String) { FLOATING("悬浮"), FIXED("固定") }
enum class PageAnimation(val title: String) { SLIDE("滑动"), FADE("淡入"), NONE("无动画") }
enum class GlassLevel(val title: String) { NONE("无"), LIGHT("轻度"), FULL("完全") }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.SLATE,
    val colorStyle: ColorStyle = ColorStyle.STANDARD,
    val pureBlack: Boolean = false,
    val bottomBar: BottomBarStyle = BottomBarStyle.FLOATING,
    val animation: PageAnimation = PageAnimation.SLIDE,
    val dynamicColor: Boolean = false,
    val swipeNavigation: Boolean = true,
    val glassLevel: GlassLevel = GlassLevel.FULL,
    val laundryEnabled: Boolean = true,
    val waterEnabled: Boolean = true,
    val splashEnabled: Boolean = true,
    val splashImages: List<String> = emptyList(),
    val splashDurationMs: Int = DEFAULT_SPLASH_DURATION_MS
) {
    val visibleTabs get() = visibleNavigationTabs(laundryEnabled, waterEnabled)
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
        mode = savedEnum("mode", ThemeMode.SYSTEM),
        palette = savedEnum("palette", ThemePalette.SLATE),
        colorStyle = savedEnum("color_style", ColorStyle.STANDARD),
        pureBlack = preferences.getBoolean("pure_black", false),
        bottomBar = savedEnum("bottom_bar", BottomBarStyle.FLOATING),
        animation = savedEnum("animation", PageAnimation.SLIDE),
        dynamicColor = preferences.getBoolean("dynamic_color", false),
        swipeNavigation = preferences.getBoolean("swipe_navigation", true),
        glassLevel = savedEnum("glass_level", GlassLevel.FULL),
        laundryEnabled = preferences.getBoolean("laundry_enabled", true),
        waterEnabled = preferences.getBoolean("water_enabled", true),
        splashEnabled = runCatching { preferences.getBoolean("splash_enabled", true) }.getOrDefault(true),
        splashImages = decodeSplashImages(runCatching { preferences.getString("splash_images", null) }.getOrNull()),
        splashDurationMs = savedSplashDurationMs(runCatching {
            preferences.getInt("splash_duration_ms", DEFAULT_SPLASH_DURATION_MS)
        }.getOrNull())
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
        val previousImages = settings.splashImages
        settings = value.copy(
            splashImages = normalizeSplashImageNames(value.splashImages),
            splashDurationMs = normalizeSplashDurationMs(value.splashDurationMs)
        )
        val editor = preferences.edit()
            .putString("mode", value.mode.name)
            .putString("palette", value.palette.name)
            .putString("color_style", value.colorStyle.name)
            .putBoolean("pure_black", value.pureBlack)
            .putString("bottom_bar", value.bottomBar.name)
            .putString("animation", value.animation.name)
            .putBoolean("dynamic_color", value.dynamicColor)
            .putBoolean("swipe_navigation", value.swipeNavigation)
            .putString("glass_level", value.glassLevel.name)
            .putBoolean("laundry_enabled", value.laundryEnabled)
            .putBoolean("water_enabled", value.waterEnabled)
            .putBoolean("splash_enabled", value.splashEnabled)
            .putString("splash_images", encodeSplashImages(settings.splashImages))
            .putInt("splash_duration_ms", settings.splashDurationMs)
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
        enumValues<T>().firstOrNull { it.name == preferences.getString(key, null) } ?: fallback
}
