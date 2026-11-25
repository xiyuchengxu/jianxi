package dev.rain.laundry.scanner

import android.hardware.Camera
import dev.rain.laundry.core.ScanCameraCapabilities
import dev.rain.laundry.core.ScanFocusMode
import dev.rain.laundry.core.scanExposureSetting
import java.util.Locale

/** Absolute camera target produced on the UI thread from a pure-model advice. */
internal data class ScanCameraTarget(
    val exposureEv: Float? = null,
    val zoomRatio: Float? = null,
    val focusMode: ScanFocusMode? = null,
)

internal data class ScanCameraReadback(
    val capabilities: ScanCameraCapabilities,
    val requestedExposureEv: Float?,
    val actualExposureIndex: Int,
    val actualFocusMode: String?,
    val userInitiated: Boolean,
)

internal fun scanCameraCapabilities(parameters: Camera.Parameters): ScanCameraCapabilities {
    val ratios = parameters.zoomRatios
    val maxZoom = if (parameters.isZoomSupported && !ratios.isNullOrEmpty()) {
        ratios.last().toFloat() / 100f
    } else {
        1f
    }
    val focusModes = parameters.supportedFocusModes
        .orEmpty()
        .map { it.lowercase(Locale.US) }
        .toSet()
    return ScanCameraCapabilities(
        exposureMinIndex = parameters.minExposureCompensation,
        exposureMaxIndex = parameters.maxExposureCompensation,
        exposureStepEv = parameters.exposureCompensationStep,
        zoomSupported = parameters.isZoomSupported && maxZoom > 1.01f,
        maxZoomRatio = maxZoom,
        focusModes = focusModes,
    )
}

/**
 * Applies only capability-checked changes. Called on ZXing's camera thread via
 * CameraInstance.changeCameraParameters(), never from the UI thread directly.
 */
internal fun applyScanCameraTarget(parameters: Camera.Parameters, target: ScanCameraTarget) {
    target.exposureEv?.let { requested ->
        val setting = scanExposureSetting(
            requested,
            parameters.minExposureCompensation,
            parameters.maxExposureCompensation,
            parameters.exposureCompensationStep,
        )
        if (setting != null && parameters.exposureCompensation != setting.index) {
            try {
                parameters.exposureCompensation = setting.index
            } catch (_: RuntimeException) {
                // Drivers can advertise a range and still reject it; readback reports this.
            }
        }
    }

    target.zoomRatio?.let { requested ->
        if (parameters.isZoomSupported) {
            val index = closestZoomIndex(parameters.zoomRatios, requested)
            if (index != null && parameters.zoom != index) {
                try {
                    parameters.zoom = index
                } catch (_: RuntimeException) {
                    // Readback and the next bounded advice handle an unsupported step.
                }
            }
        }
    }

    target.focusMode?.let { requested ->
        val mode = chooseFocusMode(parameters.supportedFocusModes.orEmpty(), requested)
        if (mode != null && parameters.focusMode != mode) {
            try {
                parameters.focusMode = mode
            } catch (_: RuntimeException) {
                // Continuous focus may be missing despite appearing in the list.
            }
        }
    }
}

private fun closestZoomIndex(ratios: List<Int>?, requestedRatio: Float): Int? {
    if (ratios.isNullOrEmpty()) return null
    val wanted = requestedRatio.coerceAtLeast(1f) * 100f
    var bestIndex = 0
    var bestRatio = ratios[0].toFloat()
    for (index in ratios.indices) {
        val ratio = ratios[index].toFloat()
        // Never overshoot the bounded target: coarse zoom ladders should stay conservative.
        if (ratio <= wanted + 0.01f && ratio >= bestRatio) {
            bestRatio = ratio
            bestIndex = index
        }
    }
    return bestIndex
}

private fun chooseFocusMode(supported: List<String>, requested: ScanFocusMode): String? {
    val modes = supported.map { it.lowercase(Locale.US) }
    fun first(vararg candidates: String): String? = candidates.firstOrNull { it in modes }

    return when (requested) {
        ScanFocusMode.CONTINUOUS -> first(
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
            Camera.Parameters.FOCUS_MODE_AUTO,
        )
        ScanFocusMode.AUTO -> first(
            Camera.Parameters.FOCUS_MODE_AUTO,
            Camera.Parameters.FOCUS_MODE_MACRO,
        )
        ScanFocusMode.MACRO -> first(
            Camera.Parameters.FOCUS_MODE_MACRO,
            Camera.Parameters.FOCUS_MODE_AUTO,
        )
        ScanFocusMode.NONE -> null
    }
}
