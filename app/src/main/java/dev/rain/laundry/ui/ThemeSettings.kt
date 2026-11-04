package dev.rain.laundry.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rain.laundry.core.visibleNavigationTabs

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
    val waterEnabled: Boolean = true
) {
    val visibleTabs get() = visibleNavigationTabs(laundryEnabled, waterEnabled)
}

/** UI preferences are independent of the encrypted login/order store. */
class ThemePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
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
        waterEnabled = preferences.getBoolean("water_enabled", true)
    ))
        private set

    fun update(value: ThemeSettings) {
        settings = value
        preferences.edit()
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
            .apply()
    }

    private inline fun <reified T : Enum<T>> savedEnum(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == preferences.getString(key, null) } ?: fallback
}
