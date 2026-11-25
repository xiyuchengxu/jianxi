package dev.rain.laundry.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperBackgroundBrightnessTest {
    @Test fun midpointLeavesTheActualWallpaperUntinted() {
        assertEquals(Color.Transparent, wallpaperBrightnessOverlay(0f))
        assertEquals(Color.Transparent, wallpaperBrightnessOverlay(Float.NaN))
    }

    @Test fun eachSideOfTheSliderTintsOnlyTowardItsOwnEndpoint() {
        assertEquals(Color.Black.copy(alpha = .25f), wallpaperBrightnessOverlay(-.25f))
        assertEquals(Color.White.copy(alpha = .5f), wallpaperBrightnessOverlay(.5f))
        assertEquals(Color.Black, wallpaperBrightnessOverlay(-2f))
        assertEquals(Color.White, wallpaperBrightnessOverlay(2f))
    }
}
