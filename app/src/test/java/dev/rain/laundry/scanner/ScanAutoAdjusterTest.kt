package dev.rain.laundry.scanner

import dev.rain.laundry.core.ScanAssistMode
import dev.rain.laundry.core.ScanAutoAdjuster
import dev.rain.laundry.core.ScanAutoConfig
import dev.rain.laundry.core.ScanAutoStatus
import dev.rain.laundry.core.ScanCameraCapabilities
import dev.rain.laundry.core.ScanDecodeAttempt
import dev.rain.laundry.core.ScanFocusMode
import dev.rain.laundry.core.ScanFrameObservation
import dev.rain.laundry.core.ScanFrameStats
import dev.rain.laundry.core.ScanPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAutoAdjusterTest {
    private val caps = ScanCameraCapabilities(
        exposureMinIndex = -12,
        exposureMaxIndex = 12,
        exposureStepEv = 0.5f,
        zoomSupported = true,
        maxZoomRatio = 4f,
        focusModes = setOf("continuous-video", "auto", "macro"),
    )

    private fun stats(
        mean: Float = 130f,
        contrast: Float = 180f,
        bright: Float = 0.10f,
        dark: Float = 0.10f,
    ) = ScanFrameStats(
        width = 100, height = 100, meanLuma = mean, contrast = contrast,
        stdDev = 50f, darkFraction = dark, brightFraction = bright, sampledPixels = 10_000,
    )

    private val overexposed = stats(mean = 232f, contrast = 28f, bright = 0.92f, dark = 0.01f)
    private val underexposed = stats(mean = 40f, contrast = 20f, bright = 0f, dark = 0.9f)
    private val neutral = stats()

    private val smallTriangle = listOf(
        ScanPoint(0.20f, 0.20f),
        ScanPoint(0.26f, 0.20f),
        ScanPoint(0.20f, 0.26f),
    )
    private val largeTriangle = listOf(
        ScanPoint(0.20f, 0.20f),
        ScanPoint(0.60f, 0.20f),
        ScanPoint(0.20f, 0.60f),
    )

    private fun frame(at: Long, stats: ScanFrameStats, points: List<ScanPoint> = emptyList()) =
        ScanFrameObservation(
            frameIndex = at,
            timestampMs = at,
            stats = stats,
            candidates = points,
            attempts = listOf(ScanDecodeAttempt.FAST),
            decoded = false,
        )

    private fun screenConfig(
        base: Float = -1.5f,
        hold: Long = 0L,
        enabled: Boolean = true,
    ) = ScanAutoConfig(
        enabled = enabled,
        mode = ScanAssistMode.SCREEN,
        baseExposureEv = base,
        manualHoldUntilMs = hold,
    )

    @Test fun recoverySharesMinimumExposureIntervalAndKeepsRecoverySpacing() {
        val adjuster = ScanAutoAdjuster()
        val fine = caps.copy(exposureStepEv = 0.25f)
        val config = screenConfig(base = 0f)
        adjuster.reset(config)

        assertNull(adjuster.observe(frame(0, overexposed), config, fine, 0).exposureEv)
        val darkened = adjuster.observe(frame(100, overexposed), config, fine, 100)
        assertEquals(-0.25f, darkened.exposureEv!!, 0.001f)

        // Four stable frames cannot recover 40 ms after a normal exposure change.
        assertNull(adjuster.observe(frame(110, neutral), config, fine, 110).exposureEv)
        assertNull(adjuster.observe(frame(120, neutral), config, fine, 120).exposureEv)
        assertNull(adjuster.observe(frame(130, neutral), config, fine, 130).exposureEv)
        assertNull(adjuster.observe(frame(140, neutral), config, fine, 140).exposureEv)
        assertNull(adjuster.observe(frame(549, neutral), config, fine, 549).exposureEv)

        val recovered = adjuster.observe(frame(550, neutral), config, fine, 550)
        assertEquals(0f, recovered.exposureEv!!, 0.001f)

        // The next darkening also waits for the shared 450 ms interval.
        assertNull(adjuster.observe(frame(600, overexposed), config, fine, 600).exposureEv)
        assertNull(adjuster.observe(frame(650, overexposed), config, fine, 650).exposureEv)
        val darkenedAgain = adjuster.observe(frame(1_000, overexposed), config, fine, 1_000)
        assertEquals(-0.25f, darkenedAgain.exposureEv!!, 0.001f)

        // Recovery also keeps its own 900 ms spacing, not just the shared 450 ms.
        assertNull(adjuster.observe(frame(1_010, neutral), config, fine, 1_010).exposureEv)
        assertNull(adjuster.observe(frame(1_020, neutral), config, fine, 1_020).exposureEv)
        assertNull(adjuster.observe(frame(1_030, neutral), config, fine, 1_030).exposureEv)
        assertNull(adjuster.observe(frame(1_040, neutral), config, fine, 1_040).exposureEv)
        assertNull(adjuster.observe(frame(1_449, neutral), config, fine, 1_449).exposureEv)
        val recoveredAgain = adjuster.observe(frame(1_450, neutral), config, fine, 1_450)
        assertEquals(0f, recoveredAgain.exposureEv!!, 0.001f)
    }

    @Test fun manualSliderHasExposurePriorityUntilHoldExpires() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig(hold = 5_000L)
        adjuster.reset(config)
        assertNull(adjuster.observe(frame(0, overexposed), config, caps, 0).exposureEv)
        val held = adjuster.observe(frame(500, overexposed), config, caps, 500)
        assertNull(held.exposureEv)
        assertEquals(ScanAutoStatus.MANUAL, held.status)

        assertNull(adjuster.observe(frame(6_000, overexposed), config, caps, 6_000).exposureEv)
        val resumed = adjuster.observe(frame(6_100, overexposed), config, caps, 6_100)
        assertNotNull(resumed.exposureEv)
        assertTrue(resumed.exposureEv!! < -1.5f)
    }

    @Test fun screenExposureDarkensAndClampsAtTheCameraMinimum() {
        val adjuster = ScanAutoAdjuster()
        val narrow = caps.copy(exposureMinIndex = -4, exposureMaxIndex = 4)
        val config = screenConfig(base = -1.5f)
        adjuster.reset(config)
        assertNull(adjuster.observe(frame(0, overexposed), config, narrow, 0).exposureEv)
        val first = adjuster.observe(frame(500, overexposed), config, narrow, 500)
        assertEquals(-2.0f, first.exposureEv!!, 0.001f)
        val atLimit = adjuster.observe(frame(1_000, overexposed), config, narrow, 1_000)
        assertNull(atLimit.exposureEv)
        assertEquals(ScanAutoStatus.LIMIT, atLimit.status)
        assertTrue(atLimit.exposureAtLimit)
    }

    @Test fun screenExposureCanBrightenADarkFrameButNotAboveNeutral() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig(base = -1.5f)
        adjuster.reset(config)
        assertNull(adjuster.observe(frame(0, underexposed), config, caps, 0).exposureEv)
        assertNull(adjuster.observe(frame(500, underexposed), config, caps, 500).exposureEv)
        val first = adjuster.observe(frame(1_000, underexposed), config, caps, 1_000)
        assertEquals(-1.0f, first.exposureEv!!, 0.001f)
        val limit = adjuster.observe(frame(1_500, underexposed), config, caps, 1_500)
        assertEquals(-0.5f, limit.exposureEv!!, 0.001f)
        val atZero = adjuster.observe(frame(2_000, underexposed), config, caps, 2_000)
        assertEquals(0f, atZero.exposureEv!!, 0.001f)
        val noMore = adjuster.observe(frame(2_500, underexposed), config, caps, 2_500)
        assertNull(noMore.exposureEv)
        assertTrue(noMore.exposureAtLimit)
    }

    @Test fun normalModeKeepsTheBaseExposureEvenWhenOverexposed() {
        val adjuster = ScanAutoAdjuster()
        val config = ScanAutoConfig(enabled = true, mode = ScanAssistMode.NORMAL, baseExposureEv = 0f)
        adjuster.reset(config)
        repeat(10) { index ->
            val advice = adjuster.observe(frame(index * 600L, overexposed), config, caps, index * 600L)
            assertNull(advice.exposureEv)
        }
    }

    @Test fun zoomRequiresSmallCoherentFinderGeometryAndCapsAtTwoX() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig()
        adjuster.reset(config)
        var now = 0L
        val targets = mutableListOf<Float>()
        repeat(16) {
            val advice = adjuster.observe(frame(now, neutral, smallTriangle), config, caps, now)
            advice.zoomRatio?.let { targets += it }
            now += 400
        }
        assertEquals(listOf(1.25f, 1.5f, 1.75f, 2.0f), targets)
        assertTrue(targets.last() <= 2.0f)
    }

    @Test fun zoomDoesNotMoveWithoutACoherentCandidateTriangle() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig()
        adjuster.reset(config)
        val onePoint = listOf(ScanPoint(0.2f, 0.2f))
        val randomPoints = listOf(
            ScanPoint(0.1f, 0.1f), ScanPoint(0.5f, 0.1f), ScanPoint(0.2f, 0.5f),
        )
        repeat(12) { index ->
            assertNull(adjuster.observe(frame(index * 500L, neutral, onePoint), config, caps, index * 500L).zoomRatio)
            assertNull(adjuster.observe(frame(index * 500L + 100, neutral, randomPoints), config, caps, index * 500L + 100).zoomRatio)
        }
    }

    @Test fun zoomRequiresStableGeometryAcrossFrames() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig()
        adjuster.reset(config)
        repeat(20) { index ->
            val points = if (index % 2 == 0) smallTriangle else largeTriangle
            val advice = adjuster.observe(frame(index * 500L, neutral, points), config, caps, index * 500L)
            assertNull("alternating geometry must not zoom", advice.zoomRatio)
        }
    }

    @Test fun zoomRecoversForALargeCodeAndWhenGeometryDisappears() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig()
        adjuster.reset(config)
        var now = 0L
        repeat(12) {
            adjuster.observe(frame(now, neutral, smallTriangle), config, caps, now)
            now += 400
        }
        var recovered: Float? = null
        repeat(8) {
            val advice = adjuster.observe(frame(now, neutral, largeTriangle), config, caps, now)
            if (advice.zoomRatio != null) recovered = advice.zoomRatio
            now += 600
        }
        assertNotNull(recovered)
        assertTrue(recovered!! < 2.0f)
    }

    @Test fun disablingAutoRestoresBaseExposureAndOneXZoom() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig()
        adjuster.reset(config)
        var now = 0L
        repeat(12) {
            adjuster.observe(frame(now, overexposed, smallTriangle), config, caps, now)
            now += 500
        }
        val disabled = config.copy(enabled = false)
        val advice = adjuster.observe(frame(now, overexposed, smallTriangle), disabled, caps, now)
        assertEquals(ScanAutoStatus.OFF, advice.status)
        assertEquals(-1.5f, advice.exposureEv!!, 0.001f)
        assertEquals(1f, advice.zoomRatio!!, 0.001f)
    }

    @Test fun focusAdviceMatchesTheAvailableCameraModes() {
        val continuous = ScanAutoAdjuster()
        val config = screenConfig()
        continuous.reset(config)
        assertEquals(
            ScanFocusMode.CONTINUOUS,
            continuous.observe(frame(0, neutral), config, caps, 0).focusMode,
        )

        val autoOnly = ScanAutoAdjuster()
        autoOnly.reset(config)
        assertEquals(
            ScanFocusMode.AUTO,
            autoOnly.observe(
                frame(0, neutral), config,
                caps.copy(focusModes = setOf("auto")), 0,
            ).focusMode,
        )

        val noFocus = ScanAutoAdjuster()
        noFocus.reset(config)
        assertNull(
            noFocus.observe(
                frame(0, neutral), config,
                caps.copy(focusModes = emptySet()), 0,
            ).focusMode,
        )
    }

    @Test fun unsupportedExposureAndZoomAreReportedNotFaked() {
        val adjuster = ScanAutoAdjuster()
        val config = screenConfig()
        val noHardware = ScanCameraCapabilities(
            exposureMinIndex = 0, exposureMaxIndex = 0, exposureStepEv = 0f,
            zoomSupported = false, maxZoomRatio = 1f, focusModes = emptySet(),
        )
        adjuster.reset(config)
        val advice = adjuster.observe(frame(0, overexposed, smallTriangle), config, noHardware, 0)
        assertNull(advice.exposureEv)
        assertNull(advice.zoomRatio)
        assertEquals(ScanAutoStatus.UNSUPPORTED, advice.status)
        assertTrue(advice.exposureUnsupported)
        assertTrue(advice.zoomUnsupported)
        assertFalse(advice.exposureAtLimit)
    }
}
