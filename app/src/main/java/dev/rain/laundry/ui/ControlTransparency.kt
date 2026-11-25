package dev.rain.laundry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalControlOpacity = compositionLocalOf { 1f }

internal fun normalizeControlTransparency(value: Float?): Float? =
    value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

internal fun ThemeSettings.effectiveControlTransparency(wallpaperVisible: Boolean): Float =
    normalizeControlTransparency(controlTransparency) ?: if (wallpaperVisible) .30f else 0f

/** Scale the paint alpha, leaving the children, text, and interaction layer alone. */
@Composable
internal fun Color.controlContainer(): Color {
    val opacity = LocalControlOpacity.current
    return if (opacity == 1f || this == Color.Unspecified) this else copy(alpha = alpha * opacity)
}
