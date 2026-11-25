package dev.rain.laundry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

internal val LocalWallpaperTextBacking = staticCompositionLocalOf { false }

/** When wallpaper is off, a plain text label has no independently painted backing to adjust. */
@Composable
internal fun AdjustableWallpaperLabel(id: String, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    if (LocalWallpaperTextBacking.current) {
        AdjustableControl(id, modifier) { target -> content(target.wallpaperTextBacking()) }
    } else content(modifier)
}

/** A small local backing for text placed directly on wallpaper, without dimming the whole page. */
@Composable
internal fun Modifier.wallpaperTextBacking(): Modifier {
    if (!LocalWallpaperTextBacking.current) return this
    return background(MaterialTheme.colorScheme.background.controlContainer(), appShape(10.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp)
        .controlForegroundOutline()
}

/** Apply to just the label or icon, after the control's background has been drawn. */
@Composable
internal fun Modifier.controlForegroundOutline(foreground: Color = Color.Unspecified): Modifier {
    if (LocalControlOpacity.current >= 1f) return this
    val darkOutline = if (foreground == Color.Unspecified) LocalLaundryDarkTheme.current
        else foreground.luminance() > .179f
    return wallpaperGlyphOutline(if (darkOutline) Color.Black else Color.White)
}

internal fun Modifier.wallpaperGlyphOutline(color: Color): Modifier = drawWithCache {
    val outline = obtainGraphicsLayer().apply { colorFilter = ColorFilter.tint(color) }
    val radius = .75.dp.toPx()
    onDrawWithContent {
        outline.record { this@onDrawWithContent.drawContent() }
        translate(left = -radius) { drawLayer(outline) }
        translate(left = radius) { drawLayer(outline) }
        translate(top = -radius) { drawLayer(outline) }
        translate(top = radius) { drawLayer(outline) }
        drawContent()
    }
}
