package dev.rain.laundry.core

import kotlin.math.roundToInt

/** Exposure compensation is measured in device-specific steps, not milliseconds. */
data class ScanExposureSetting(
    val index: Int,
    val actualEv: Float,
    val canDarken: Boolean,
)

/** Returns null when the driver does not expose a usable compensation range. */
fun scanExposureSetting(
    targetEv: Float,
    minimum: Int,
    maximum: Int,
    stepEv: Float,
): ScanExposureSetting? {
    if (!targetEv.isFinite() || !stepEv.isFinite() || stepEv <= 0f ||
        minimum > 0 || maximum < 0 || minimum >= maximum
    ) return null

    // Use Double before rounding so very small positive steps cannot overflow to Infinity.
    val index = (targetEv.toDouble() / stepEv.toDouble()).roundToInt().coerceIn(minimum, maximum)
    return ScanExposureSetting(index, (index.toDouble() * stepEv).toFloat(), minimum < 0)
}
