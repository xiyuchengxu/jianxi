package dev.rain.laundry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanExposureTest {
    @Test fun convertsEvUsingTheCamerasStep() {
        val halfStep = requireNotNull(scanExposureSetting(-1.5f, -6, 6, 0.5f))
        assertEquals(-3, halfStep.index)
        assertEquals(-1.5f, halfStep.actualEv, 0.001f)
        assertTrue(halfStep.canDarken)
        assertEquals(-5, scanExposureSetting(-1.5f, -12, 12, 0.3f)?.index)
    }

    @Test fun clampsToTheDeviceLimitAndCanRestoreNeutral() {
        val limited = requireNotNull(scanExposureSetting(-3f, -2, 5, 0.5f))
        assertEquals(-2, limited.index)
        assertEquals(-1f, limited.actualEv, 0.001f)
        assertEquals(0, scanExposureSetting(0f, -2, 5, 0.5f)?.index)
        assertEquals(5, scanExposureSetting(100f, -2, 5, 0.5f)?.index)
    }

    @Test fun rejectsUnavailableAndMalformedCapabilities() {
        assertNull(scanExposureSetting(-1.5f, 0, 0, 0f))
        assertNull(scanExposureSetting(-1.5f, -3, 3, 0f))
        assertNull(scanExposureSetting(-1.5f, -3, 3, -0.5f))
        assertNull(scanExposureSetting(-1.5f, -3, 3, Float.NaN))
        assertNull(scanExposureSetting(-1.5f, -3, 3, Float.POSITIVE_INFINITY))
        assertNull(scanExposureSetting(Float.NaN, -3, 3, 0.5f))
        assertNull(scanExposureSetting(Float.NEGATIVE_INFINITY, -3, 3, 0.5f))
        assertNull(scanExposureSetting(-1.5f, 4, -4, 0.5f))
        assertNull(scanExposureSetting(-1.5f, 1, 4, 0.5f))
        assertNull(scanExposureSetting(-1.5f, -4, -1, 0.5f))
    }

    @Test fun handlesAsymmetricAndExtremelySmallStepRanges() {
        val brighteningOnly = requireNotNull(scanExposureSetting(-1.5f, 0, 4, 0.5f))
        assertEquals(0, brighteningOnly.index)
        assertFalse(brighteningOnly.canDarken)
        assertEquals(-4, scanExposureSetting(-1.5f, -4, 0, 0.25f)?.index)
        assertEquals(-6, scanExposureSetting(-1.5f, -6, 6, Float.MIN_VALUE)?.index)
    }
}
