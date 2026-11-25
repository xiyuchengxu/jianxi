package dev.rain.laundry.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure auto-adjustment policy. It consumes only frame statistics, coherent candidate
 * geometry and camera capabilities; time is used as a rate limit, never as the reason
 * to change a camera parameter.
 */
class ScanAutoAdjuster {
    private var mode = ScanAssistMode.NORMAL
    private var baseExposureEv = 0f
    private var currentExposureEv = 0f
    private var currentZoomRatio = 1f
    private var focusSuggested = false

    private var overFrames = 0
    private var underFrames = 0
    private var stableFrames = 0
    private var smallCodeFrames = 0
    private var largeCodeFrames = 0
    private var missingGeometryFrames = 0
    private var lastGeometrySpan = 0f
    private var lastGeometryWasValid = false

    private var lastExposureChangeMs = Long.MIN_VALUE / 2
    private var lastZoomChangeMs = Long.MIN_VALUE / 2
    private var lastRecoveryMs = Long.MIN_VALUE / 2

    fun reset(config: ScanAutoConfig) {
        mode = config.mode
        baseExposureEv = config.baseExposureEv
        currentExposureEv = config.baseExposureEv
        currentZoomRatio = 1f
        focusSuggested = false
        clearStreaks()
        lastExposureChangeMs = Long.MIN_VALUE / 2
        lastZoomChangeMs = Long.MIN_VALUE / 2
        lastRecoveryMs = Long.MIN_VALUE / 2
    }

    fun observe(
        frame: ScanFrameObservation,
        config: ScanAutoConfig,
        caps: ScanCameraCapabilities,
        nowMs: Long,
    ): ScanAutoAdvice {
        if (config.mode != mode) reset(config)
        if (config.baseExposureEv != baseExposureEv) {
            baseExposureEv = config.baseExposureEv
            currentExposureEv = config.baseExposureEv
        }

        var exposureAdvice: Float? = null
        var zoomAdvice: Float? = null
        var focusAdvice: ScanFocusMode? = null
        var exposureAtLimit = false
        var zoomAtLimit = false

        if (!config.enabled) {
            if (config.baseExposureEv != currentExposureEv) {
                currentExposureEv = config.baseExposureEv
                exposureAdvice = currentExposureEv
            }
            if (currentZoomRatio != 1f) {
                currentZoomRatio = 1f
                zoomAdvice = currentZoomRatio
            }
            clearStreaks()
            return ScanAutoAdvice(
                exposureEv = exposureAdvice,
                zoomRatio = zoomAdvice,
                status = ScanAutoStatus.OFF,
                exposureUnsupported = !caps.exposureSupported,
                zoomUnsupported = !caps.zoomSupported,
            )
        }

        if (!focusSuggested) {
            val preferred = preferredFocusMode(caps)
            if (preferred != ScanFocusMode.NONE) {
                focusAdvice = preferred
                focusSuggested = true
            }
        }

        val manualHold = nowMs < config.manualHoldUntilMs
        if (manualHold) {
            overFrames = 0
            underFrames = 0
            stableFrames = 0
        } else if (config.mode == ScanAssistMode.SCREEN && caps.exposureSupported) {
            val exposure = autoExposure(
                stats = frame.stats,
                caps = caps,
                nowMs = nowMs,
            )
            exposureAdvice = exposure.first
            exposureAtLimit = exposure.second
        } else {
            resetExposureTowardBase(caps)
        }

        val zoom = autoZoom(frame, caps, nowMs)
        zoomAdvice = zoom.first
        zoomAtLimit = zoom.second

        val changed = exposureAdvice != null || zoomAdvice != null
        val partialUnsupported = !caps.exposureSupported || !caps.zoomSupported
        val status = when {
            manualHold -> ScanAutoStatus.MANUAL
            changed -> ScanAutoStatus.ADJUSTING
            exposureAtLimit || zoomAtLimit -> ScanAutoStatus.LIMIT
            !caps.exposureSupported && !caps.zoomSupported -> ScanAutoStatus.UNSUPPORTED
            partialUnsupported -> ScanAutoStatus.LIMITED
            else -> ScanAutoStatus.STABLE
        }

        return ScanAutoAdvice(
            exposureEv = exposureAdvice,
            zoomRatio = zoomAdvice,
            focusMode = focusAdvice,
            status = status,
            exposureAtLimit = exposureAtLimit,
            zoomAtLimit = zoomAtLimit,
            exposureUnsupported = !caps.exposureSupported,
            zoomUnsupported = !caps.zoomSupported,
        )
    }

    private fun autoExposure(
        stats: ScanFrameStats,
        caps: ScanCameraCapabilities,
        nowMs: Long,
    ): Pair<Float?, Boolean> {
        if (stats.overexposed) {
            overFrames++
            underFrames = 0
            stableFrames = 0
        } else if (stats.underexposed) {
            underFrames++
            overFrames = 0
            stableFrames = 0
        } else {
            overFrames = 0
            underFrames = 0
            stableFrames++
        }

        if (overFrames >= REQUIRED_OVER_FRAMES && nowMs - lastExposureChangeMs >= EXPOSURE_INTERVAL_MS) {
            return adjustExposure(-exposureStepFor(caps), caps, nowMs, brighten = false)
        }
        if (underFrames >= REQUIRED_UNDER_FRAMES && nowMs - lastExposureChangeMs >= EXPOSURE_INTERVAL_MS) {
            return adjustExposure(exposureStepFor(caps), caps, nowMs, brighten = true)
        }
        if (stableFrames >= REQUIRED_STABLE_FRAMES &&
            nowMs - lastExposureChangeMs >= EXPOSURE_INTERVAL_MS &&
            nowMs - lastRecoveryMs >= RECOVERY_INTERVAL_MS &&
            abs(currentExposureEv - baseExposureEv) > 0.001f
        ) {
            val recovered = moveExposureToward(currentExposureEv, baseExposureEv, exposureStepFor(caps))
            return setExposure(recovered, caps, nowMs, recovery = true)
        }
        return null to false
    }

    private fun adjustExposure(
        delta: Float,
        caps: ScanCameraCapabilities,
        nowMs: Long,
        brighten: Boolean,
    ): Pair<Float?, Boolean> {
        val minEv = minExposureEv(caps)
        val maxEv = if (brighten) min(0f, maxExposureEv(caps)) else maxExposureEv(caps)
        val requested = (currentExposureEv + delta).coerceIn(minEv, maxEv)
        val atLimit = if (brighten) {
            requested >= maxEv - 0.001f && currentExposureEv >= maxEv - 0.001f
        } else {
            requested <= minEv + 0.001f && currentExposureEv <= minEv + 0.001f
        }
        return setExposure(requested, caps, nowMs, recovery = false, atLimit = atLimit)
    }

    private fun setExposure(
        requested: Float,
        caps: ScanCameraCapabilities,
        nowMs: Long,
        recovery: Boolean,
        atLimit: Boolean = false,
    ): Pair<Float?, Boolean> {
        val actual = scanExposureSetting(
            requested,
            caps.exposureMinIndex,
            caps.exposureMaxIndex,
            caps.exposureStepEv,
        )?.actualEv ?: return null to false
        if (abs(actual - currentExposureEv) < 0.001f) {
            return null to atLimit
        }
        currentExposureEv = actual
        lastExposureChangeMs = nowMs
        if (recovery) {
            lastRecoveryMs = nowMs
        }
        return actual to atLimit
    }

    private fun resetExposureTowardBase(caps: ScanCameraCapabilities) {
        overFrames = 0
        underFrames = 0
        stableFrames = 0
        if (abs(currentExposureEv - baseExposureEv) <= 0.001f) return
        currentExposureEv = scanExposureSetting(
            baseExposureEv,
            caps.exposureMinIndex,
            caps.exposureMaxIndex,
            caps.exposureStepEv,
        )?.actualEv ?: baseExposureEv
    }

    private fun autoZoom(
        frame: ScanFrameObservation,
        caps: ScanCameraCapabilities,
        nowMs: Long,
    ): Pair<Float?, Boolean> {
        val geometry = estimateCodeGeometry(frame.candidates, frame.stats.width, frame.stats.height)
        if (geometry.validFinderTriple) {
            missingGeometryFrames = 0
            val stable = lastGeometryWasValid &&
                abs(geometry.spanFraction - lastGeometrySpan) <= max(0.03f, lastGeometrySpan * 0.25f)
            lastGeometrySpan = geometry.spanFraction
            lastGeometryWasValid = true
            if (!stable) {
                smallCodeFrames = 0
                largeCodeFrames = 0
            } else if (geometry.spanFraction < ScanCodeGeometry.MIN_CODE_SPAN) {
                smallCodeFrames++
                largeCodeFrames = 0
            } else if (geometry.spanFraction > ScanCodeGeometry.LARGE_CODE_SPAN) {
                largeCodeFrames++
                smallCodeFrames = 0
            } else {
                smallCodeFrames = 0
                largeCodeFrames = 0
            }
        } else {
            missingGeometryFrames++
            lastGeometryWasValid = false
            smallCodeFrames = 0
            largeCodeFrames = 0
        }

        if (!caps.zoomSupported) {
            if (currentZoomRatio != 1f) {
                currentZoomRatio = 1f
                return 1f to false
            }
            return null to false
        }

        val maxZoom = caps.maxUsefulZoomRatio
        val canZoomIn = smallCodeFrames >= REQUIRED_SMALL_CODE_FRAMES &&
            currentZoomRatio < maxZoom - 0.01f &&
            !frame.stats.underexposed &&
            nowMs - lastZoomChangeMs >= ZOOM_INTERVAL_MS
        if (canZoomIn) {
            val target = min(maxZoom, currentZoomRatio + ZOOM_STEP)
            currentZoomRatio = target
            lastZoomChangeMs = nowMs
            smallCodeFrames = 0
            return target to (target >= maxZoom - 0.01f)
        }

        val canZoomOut = largeCodeFrames >= REQUIRED_LARGE_CODE_FRAMES &&
            currentZoomRatio > 1.01f &&
            nowMs - lastZoomChangeMs >= ZOOM_OUT_INTERVAL_MS
        if (canZoomOut) {
            val target = max(1f, currentZoomRatio - ZOOM_STEP)
            currentZoomRatio = target
            lastZoomChangeMs = nowMs
            largeCodeFrames = 0
            return target to false
        }

        val canRecover = missingGeometryFrames >= REQUIRED_MISSING_GEOMETRY_FRAMES &&
            currentZoomRatio > 1.01f &&
            nowMs - lastZoomChangeMs >= ZOOM_RECOVERY_INTERVAL_MS
        if (canRecover) {
            val target = max(1f, currentZoomRatio - ZOOM_STEP)
            currentZoomRatio = target
            lastZoomChangeMs = nowMs
            missingGeometryFrames = 0
            return target to false
        }

        val atLimit = smallCodeFrames >= REQUIRED_SMALL_CODE_FRAMES && currentZoomRatio >= maxZoom - 0.01f
        return null to atLimit
    }

    private fun clearStreaks() {
        overFrames = 0
        underFrames = 0
        stableFrames = 0
        smallCodeFrames = 0
        largeCodeFrames = 0
        missingGeometryFrames = 0
        lastGeometrySpan = 0f
        lastGeometryWasValid = false
    }

    private fun minExposureEv(caps: ScanCameraCapabilities): Float =
        caps.exposureMinIndex * caps.exposureStepEv

    private fun maxExposureEv(caps: ScanCameraCapabilities): Float =
        caps.exposureMaxIndex * caps.exposureStepEv

    private companion object {
        const val REQUIRED_OVER_FRAMES = 2
        const val REQUIRED_UNDER_FRAMES = 3
        const val REQUIRED_STABLE_FRAMES = 4
        const val REQUIRED_SMALL_CODE_FRAMES = 3
        const val REQUIRED_LARGE_CODE_FRAMES = 4
        const val REQUIRED_MISSING_GEOMETRY_FRAMES = 10
        const val EXPOSURE_INTERVAL_MS = 450L
        const val RECOVERY_INTERVAL_MS = 900L
        const val ZOOM_INTERVAL_MS = 900L
        const val ZOOM_OUT_INTERVAL_MS = 900L
        const val ZOOM_RECOVERY_INTERVAL_MS = 1_200L
        const val ZOOM_STEP = 0.25f
    }
}

data class ScanFrameObservation(
    val frameIndex: Long,
    val timestampMs: Long,
    val stats: ScanFrameStats,
    val candidates: List<ScanPoint>,
    val attempts: List<ScanDecodeAttempt>,
    val decoded: Boolean,
    val decodeMillis: Long = 0L,
)

private fun preferredFocusMode(caps: ScanCameraCapabilities): ScanFocusMode = when {
    caps.continuousFocusSupported -> ScanFocusMode.CONTINUOUS
    caps.autoFocusSupported -> ScanFocusMode.AUTO
    caps.macroFocusSupported -> ScanFocusMode.MACRO
    else -> ScanFocusMode.NONE
}
