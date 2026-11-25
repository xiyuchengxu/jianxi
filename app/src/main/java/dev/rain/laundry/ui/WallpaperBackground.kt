package dev.rain.laundry.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

internal fun normalizeWallpaperBrightness(value: Float): Float =
    if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f

/** Draw once behind page content, preserving the original wallpaper at the midpoint. */
internal fun wallpaperBrightnessOverlay(brightness: Float): Color {
    val level = normalizeWallpaperBrightness(brightness)
    if (level == 0f) return Color.Transparent
    val tint = if (level < 0f) Color.Black else Color.White
    return tint.copy(alpha = abs(level))
}
