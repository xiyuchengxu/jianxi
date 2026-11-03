package dev.rain.laundry.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun everyPaletteKeepsReadableTextInBothModesAndAllStyles() {
        val failures = mutableListOf<String>()
        for (palette in ThemePalette.entries) for (style in ColorStyle.entries) {
            for (dark in listOf(false, true)) for (black in listOf(false, true)) {
                val colors = laundryColorScheme(ThemeSettings(palette = palette, colorStyle = style, pureBlack = black), dark)
                val pairs = listOf(
                    "button" to (colors.primary to colors.onPrimary),
                    "page" to (colors.background to colors.onBackground),
                    "card" to (colors.surfaceContainer to colors.onSurface),
                    "card secondary text" to (colors.surfaceContainer to colors.onSurfaceVariant),
                    "card colored label" to (colors.surfaceContainer to colors.primary),
                    "wash card" to (colors.primaryContainer to colors.onPrimaryContainer),
                    "wash card button" to (colors.primaryContainer to colors.primary),
                    "tonal button" to (colors.secondaryContainer to colors.onSecondaryContainer)
                )
                for ((role, pair) in pairs) {
                    val ratio = contrast(pair.first, pair.second)
                    if (ratio < 4.5f) failures += "$palette/$style dark=$dark black=$black $role contrast=$ratio"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
}
