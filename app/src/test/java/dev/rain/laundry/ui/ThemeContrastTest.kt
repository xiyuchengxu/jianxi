package dev.rain.laundry.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun miuixBridgeUsesTheInstalledLibraryColors() {
        for (dark in listOf(false, true)) for (black in listOf(false, true)) {
            val upstream = if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme()
                else top.yukonga.miuix.kmp.theme.lightColorScheme()
            val native = miuixLibraryColors(black, dark)
            val colors = laundryColorScheme(ThemeSettings(designStyle = DesignStyle.MIUIX, pureBlack = black), dark)
            assertEquals(upstream.primary, native.primary)
            assertEquals(if (dark) {
                if (black) Color.Black else Color(0xFF141414)
            } else upstream.background, native.background)
            assertEquals(native.primary, colors.primary)
            assertEquals(native.background, colors.background)
            assertEquals(native.surface, colors.surfaceContainer)
            assertEquals(native.tertiaryContainer, colors.primaryContainer)
            assertEquals(native.onSurface, colors.onSurface)
            if (dark && black) assertEquals(Color.Black, colors.surfaceContainer)
        }
    }

    @Test fun miuixDarkNeutralEdgesTurnPureBlackOnlyWhenEnhanced() {
        val normal = miuixLibraryColors(pureBlack = false, dark = true)
        val enhanced = miuixLibraryColors(pureBlack = true, dark = true)
        val upstream = top.yukonga.miuix.kmp.theme.darkColorScheme()
        assertTrue(normal.background != Color.Black)
        for (edge in listOf(normal.outline, normal.dividerLine, normal.surface,
            normal.secondaryContainer, normal.secondaryVariant)) {
            assertTrue("Ordinary dark neutrals must remain grey", edge.red == edge.green && edge.green == edge.blue)
            assertTrue("Ordinary dark edges must remain distinct from pure black", edge != Color.Black)
        }
        for (edge in listOf(enhanced.background, enhanced.outline, enhanced.dividerLine,
            enhanced.surface, enhanced.surfaceVariant, enhanced.surfaceContainer,
            enhanced.surfaceContainerHigh, enhanced.surfaceContainerHighest,
            enhanced.secondaryContainer, enhanced.secondaryVariant,
            enhanced.disabledSecondary, enhanced.disabledSecondaryVariant)) {
            assertEquals(Color.Black, edge)
        }
        assertEquals(upstream.primary, enhanced.primary)
        assertEquals(upstream.tertiaryContainer, enhanced.tertiaryContainer)
        for (black in listOf(false, true)) {
            val bridge = laundryColorScheme(ThemeSettings(designStyle = DesignStyle.MIUIX, pureBlack = black), true)
            val native = if (black) enhanced else normal
            assertEquals(native.outline, bridge.outline)
            assertEquals(native.dividerLine, bridge.outlineVariant)
            assertEquals(native.background, bridge.background)
            assertTrue("Selection blue must remain visible", contrast(bridge.primary, bridge.surfaceContainer) >= 3f)
            assertTrue("Errors must remain readable", contrast(bridge.error, bridge.surfaceContainer) >= 4.5f)
        }
    }

    @Test fun miuixLightNeutralsIgnoreDarkEnhancement() {
        val normal = miuixLibraryColors(pureBlack = false, dark = false)
        val enhanced = miuixLibraryColors(pureBlack = true, dark = false)
        val upstream = top.yukonga.miuix.kmp.theme.lightColorScheme()
        assertEquals(upstream.background, normal.background)
        assertEquals(upstream.surface, normal.surface)
        assertEquals(upstream.outline, normal.outline)
        assertEquals(upstream.dividerLine, normal.dividerLine)
        assertEquals(normal.background, enhanced.background)
        assertEquals(normal.surface, enhanced.surface)
        assertEquals(normal.outline, enhanced.outline)
        assertEquals(normal.dividerLine, enhanced.dividerLine)
    }

    @Test fun miuixBodyTextStaysReadableInBothModes() {
        // These are the actual text-bearing bridge roles. Miuix button colors are owned by
        // the library and are not the former emulated Material secondary/tertiary palette.
        for (dark in listOf(false, true)) for (black in listOf(false, true)) {
            val colors = laundryColorScheme(ThemeSettings(designStyle = DesignStyle.MIUIX, pureBlack = black), dark)
            val pairs = listOf(
                colors.primaryContainer to colors.onPrimaryContainer,
                colors.secondaryContainer to colors.onSecondaryContainer,
                colors.background to colors.onBackground,
                colors.surfaceContainer to colors.onSurface,
                colors.surfaceContainer to colors.onSurfaceVariant
            )
            assertTrue("MIUIX dark=$dark", pairs.all { (background, foreground) ->
                contrast(background, foreground) >= 4.5f
            })
        }
    }

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
