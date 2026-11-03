package dev.rain.laundry.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.rain.laundry.vendor.material.hct.Hct
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WallpaperThemeTest {
    private val purple = 0xFF564280.toInt()
    private val cyan = Color(0xFF00CDD1)

    @Test fun purpleWallpaperWinsEvenWhenSystemAccentIsCyan() {
        val seed = selectWallpaperSeed(listOf(Color.Black.toArgb(), purple, Color.Gray.toArgb()))
        assertEquals(purple, seed)
        for (dark in listOf(false, true)) {
            val system = if (dark) darkColorScheme(primary = cyan) else lightColorScheme(primary = cyan)
            val actual = resolveWallpaperColorScheme(seed, system, dark, false)
            assertNotEquals(cyan, actual.primary)
            assertTrue("Wallpaper hue must be preserved", hueDifference(purple, actual.primary.toArgb()) < 8.0)
        }
    }

    @Test fun coloredSecondaryWallpaperSampleIsPreferredToBlackOrWhite() {
        assertEquals(purple, selectWallpaperSeed(listOf(Color.Black.toArgb(), purple)))
        assertEquals(purple, selectWallpaperSeed(listOf(Color.White.toArgb(), Color.Gray.toArgb(), purple)))
    }

    @Test fun emptyWallpaperMetadataIsDistinctFromARealBlackWallpaper() {
        assertNull(selectWallpaperSeed(emptyList()))
        assertEquals(Color.Black.toArgb(), selectWallpaperSeed(listOf(Color.Black.toArgb())))
    }

    @Test fun monochromeWallpaperDoesNotInventAnAccentHue() {
        for (seed in listOf(Color.Black.toArgb(), Color.Gray.toArgb(), Color.White.toArgb())) {
            for (dark in listOf(false, true)) {
                val scheme = wallpaperSeedColorScheme(seed, dark)
                assertTrue(Hct.fromInt(scheme.primary.toArgb()).chroma < 4.0)
            }
        }
    }

    @Test fun unavailableWallpaperMetadataRetainsTheSystemFallback() {
        val system = darkColorScheme(primary = cyan)
        assertSame(system, resolveWallpaperColorScheme(null, system, true, false))
    }

    @Test fun pureBlackChangesBackgroundWithoutReplacingTheWallpaperOrFallbackAccent() {
        val system = darkColorScheme(primary = cyan)
        for (seed in listOf(purple, null)) {
            val normal = resolveWallpaperColorScheme(seed, system, true, false)
            val black = resolveWallpaperColorScheme(seed, system, true, true)
            assertEquals(Color.Black, black.background)
            assertEquals(Color.Black, black.surface)
            assertEquals(normal.primary, black.primary)
        }
    }

    @Test fun differentWallpapersGenerateDifferentThemeHues() {
        val first = wallpaperSeedColorScheme(purple, true).primary
        val second = wallpaperSeedColorScheme(0xFFB3261E.toInt(), true).primary
        assertTrue(hueDifference(first.toArgb(), second.toArgb()) > 40.0)
    }

    @Test fun wallpaperSchemesKeepReadableTextAcrossChromaticAndNeutralSeeds() {
        val seeds = listOf(purple, 0xFF315DA8.toInt(), 0xFF168A48.toInt(), 0xFFFF9900.toInt(),
            0xFFFFFF00.toInt(), 0xFFBB2222.toInt(), Color.Black.toArgb(), Color.Gray.toArgb(), Color.White.toArgb())
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) for (black in listOf(false, true)) {
            val scheme = resolveWallpaperColorScheme(seed, lightColorScheme(), dark, black)
            val pairs = listOf(scheme.primary to scheme.onPrimary, scheme.background to scheme.onBackground,
                scheme.surfaceContainer to scheme.onSurface, scheme.surfaceContainer to scheme.onSurfaceVariant,
                scheme.surfaceContainer to scheme.primary, scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.primaryContainer to scheme.primary, scheme.secondaryContainer to scheme.onSecondaryContainer)
            pairs.forEachIndexed { index, (a, b) ->
                val ratio = (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
                if (ratio < 4.5f) failures += "seed=$seed dark=$dark black=$black pair=$index contrast=$ratio"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun hueDifference(first: Int, second: Int): Double {
        val difference = abs(Hct.fromInt(first).hue - Hct.fromInt(second).hue)
        return minOf(difference, 360.0 - difference)
    }
}
