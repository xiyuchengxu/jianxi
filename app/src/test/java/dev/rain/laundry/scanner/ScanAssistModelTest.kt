package dev.rain.laundry.scanner

import dev.rain.laundry.core.ScanAssistSession
import dev.rain.laundry.core.ScanCameraCapabilities
import dev.rain.laundry.core.ScanCodeGeometry
import dev.rain.laundry.core.ScanDecodeAttempt
import dev.rain.laundry.core.ScanFrameAnalyzer
import dev.rain.laundry.core.ScanFrameStats
import dev.rain.laundry.core.ScanPoint
import dev.rain.laundry.core.estimateCodeGeometry
import dev.rain.laundry.core.planScanDecode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAssistModelTest {
    private fun highContrast() = ScanFrameStats(
        width = 100, height = 100, meanLuma = 140f, contrast = 210f,
        stdDev = 80f, darkFraction = 0.45f, brightFraction = 0.45f, sampledPixels = 10_000,
    )

    private fun lowContrast() = ScanFrameStats(
        width = 100, height = 100, meanLuma = 112f, contrast = 24f,
        stdDev = 12f, darkFraction = 0f, brightFraction = 0f, sampledPixels = 10_000,
    )

    @Test fun analyzerFindsBrightnessContrastAndClipping() {
        val split = ByteArray(100 * 100) {
            if (it % 100 < 50) 0.toByte() else 255.toByte()
        }
        val brightStats = ScanFrameAnalyzer.analyze(split, 100, 100, targetSamples = 100)
        assertTrue(brightStats.valid)
        assertTrue(brightStats.contrast > 200f)
        assertFalse("A high-contrast QR is not glare just because it has white modules", brightStats.overexposed)
        assertTrue(brightStats.darkFraction > 0.4f)
        assertTrue(brightStats.brightFraction > 0.4f)

        val washedOut = ScanFrameAnalyzer.analyze(ByteArray(100 * 100) { 255.toByte() }, 100, 100)
        assertTrue(washedOut.overexposed)
        assertTrue(washedOut.contrast < 1f)

        val flat = ByteArray(100 * 100) { 120 }
        val flatStats = ScanFrameAnalyzer.analyze(flat, 100, 100, targetSamples = 100)
        assertTrue(flatStats.lowContrast)
        assertFalse(flatStats.overexposed)
        assertFalse(flatStats.underexposed)

        val dark = ByteArray(100 * 100) { 20 }
        assertTrue(ScanFrameAnalyzer.analyze(dark, 100, 100).underexposed)
        assertFalse(ScanFrameAnalyzer.analyze(ByteArray(10), 100, 100).valid)
        assertFalse(ScanFrameAnalyzer.analyze(ByteArray(100), 0, 10).valid)
    }

    @Test fun decodePlanAlwaysStartsFastAndUsesAtMostOneFallback() {
        assertEquals(listOf(ScanDecodeAttempt.FAST), planScanDecode(highContrast(), 0).attempts)
        assertEquals(
            listOf(ScanDecodeAttempt.FAST, ScanDecodeAttempt.INVERTED),
            planScanDecode(highContrast(), 1).attempts,
        )
        assertEquals(
            listOf(ScanDecodeAttempt.FAST, ScanDecodeAttempt.LOW_CONTRAST),
            planScanDecode(lowContrast(), 1).attempts,
        )
        assertEquals(
            listOf(ScanDecodeAttempt.FAST, ScanDecodeAttempt.HARD),
            planScanDecode(highContrast(), 3).attempts,
        )
        assertEquals(
            listOf(ScanDecodeAttempt.FAST, ScanDecodeAttempt.INVERTED),
            planScanDecode(highContrast(), 5).attempts,
        )
        assertEquals(
            listOf(ScanDecodeAttempt.FAST, ScanDecodeAttempt.HARD_LOW_CONTRAST),
            planScanDecode(highContrast(), 4).attempts,
        )
        assertEquals(
            listOf(ScanDecodeAttempt.FAST, ScanDecodeAttempt.HARD_LOW_CONTRAST),
            planScanDecode(lowContrast(), 4).attempts,
        )
        for (streak in 0..20) {
            val attempts = planScanDecode(lowContrast(), streak).attempts
            assertEquals(ScanDecodeAttempt.FAST, attempts.first())
            assertTrue("streak=$streak attempts=$attempts", attempts.size <= 2)
        }
        assertFalse(planScanDecode(highContrast(), 2).attempts.contains(ScanDecodeAttempt.HARD))
    }

    @Test fun finderTripleMustBeCoherentAndSmallCodeIsMeasured() {
        val small = listOf(
            ScanPoint(0.20f, 0.20f),
            ScanPoint(0.26f, 0.20f),
            ScanPoint(0.20f, 0.26f),
        )
        val smallGeometry = estimateCodeGeometry(small, 100, 100)
        assertTrue(smallGeometry.validFinderTriple)
        assertTrue(smallGeometry.spanFraction < ScanCodeGeometry.MIN_CODE_SPAN)

        val large = listOf(
            ScanPoint(0.20f, 0.20f),
            ScanPoint(0.60f, 0.20f),
            ScanPoint(0.20f, 0.60f),
        )
        val largeGeometry = estimateCodeGeometry(large, 100, 100)
        assertTrue(largeGeometry.validFinderTriple)
        assertTrue(largeGeometry.spanFraction > ScanCodeGeometry.LARGE_CODE_SPAN)

        assertFalse(estimateCodeGeometry(small.take(2), 100, 100).validFinderTriple)
        assertFalse(
            estimateCodeGeometry(
                listOf(ScanPoint(0.1f, 0.1f), ScanPoint(0.2f, 0.1f), ScanPoint(0.3f, 0.1f)),
                100, 100,
            ).validFinderTriple,
        )
        assertFalse(
            estimateCodeGeometry(
                listOf(ScanPoint(0.1f, 0.1f), ScanPoint(0.5f, 0.1f), ScanPoint(0.5f, 0.2f)),
                100, 100,
            ).validFinderTriple,
        )
    }

    @Test fun sessionRejectsCallbacksFromAStoppedPreview() {
        val session = ScanAssistSession()
        val first = session.start()
        assertTrue(session.isActive(first))
        session.stop()
        assertFalse(session.isActive(first))
        val second = session.start()
        assertTrue(session.isActive(second))
        assertFalse(session.isActive(first))
    }

    @Test fun capabilitiesExposeOnlySupportedHardwareRanges() {
        val full = ScanCameraCapabilities(
            exposureMinIndex = -6, exposureMaxIndex = 6, exposureStepEv = 0.5f,
            zoomSupported = true, maxZoomRatio = 4f,
            focusModes = setOf("continuous-video", "auto"),
        )
        assertTrue(full.exposureSupported)
        assertTrue(full.canDarken)
        assertTrue(full.continuousFocusSupported)
        assertEquals(2f, full.maxUsefulZoomRatio, 0.001f)

        val brightenOnly = ScanCameraCapabilities(
            exposureMinIndex = 0, exposureMaxIndex = 4, exposureStepEv = 0.5f,
            zoomSupported = false, maxZoomRatio = 1f,
        )
        assertTrue(brightenOnly.exposureSupported)
        assertFalse(brightenOnly.canDarken)
        assertEquals(1f, brightenOnly.maxUsefulZoomRatio, 0.001f)

        val unavailable = ScanCameraCapabilities(
            exposureMinIndex = 0, exposureMaxIndex = 0, exposureStepEv = 0f,
            zoomSupported = false, maxZoomRatio = 1f,
        )
        assertFalse(unavailable.exposureSupported)
        assertFalse(unavailable.canDarken)
    }
}
