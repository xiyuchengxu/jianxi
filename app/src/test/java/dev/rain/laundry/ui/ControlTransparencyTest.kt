package dev.rain.laundry.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlTransparencyTest {
    @Test fun existingSettingsUseTheWallpaperDefaultUntilTheUserChoosesAValue() {
        val settings = ThemeSettings()
        assertEquals(0f, settings.effectiveControlTransparency(false), 0f)
        assertEquals(.30f, settings.effectiveControlTransparency(true), 0f)
        for (value in listOf(0f, .6f, 1f)) {
            val manual = settings.copy(controlTransparency = value)
            assertEquals(value, manual.effectiveControlTransparency(false), 0f)
            assertEquals(value, manual.effectiveControlTransparency(true), 0f)
        }
    }

    @Test fun invalidPreferenceFallsBackAndFiniteValuesAreClamped() {
        assertEquals(null, normalizeControlTransparency(Float.NaN))
        assertEquals(null, normalizeControlTransparency(Float.POSITIVE_INFINITY))
        assertEquals(0f, normalizeControlTransparency(-.1f)!!, 0f)
        assertEquals(1f, normalizeControlTransparency(1.1f)!!, 0f)
    }
}
