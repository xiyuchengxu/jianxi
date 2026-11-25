package dev.rain.laundry.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure scan-assist building blocks. Nothing here touches Android, ZXing or a camera;
 * the decoder thread and JVM tests use the same policies.
 */

enum class ScanAssistMode { NORMAL, SCREEN }

enum class ScanDecodeAttempt { FAST, INVERTED, LOW_CONTRAST, HARD, HARD_LOW_CONTRAST }

data class ScanDecodePlan(val attempts: List<ScanDecodeAttempt>)

/**
 * A bounded plan for one camera frame.
 *
 * FAST is always the normal-polarity HybridBinarizer attempt. TRY_HARDER is never part
 * of it: ZXing's FinderPatternFinder checks for the hint key, not its boolean value.
 * After a failure at most one fallback is added. Inverted/low-contrast attempts are
 * frequent enough for a stationary code, while more expensive detector/binarizer
 * combinations are retried periodically instead of on every frame.
 */
fun planScanDecode(stats: ScanFrameStats, failureStreak: Int): ScanDecodePlan {
    val attempts = mutableListOf(ScanDecodeAttempt.FAST)
    if (failureStreak <= 0) return ScanDecodePlan(attempts)

    val lowContrast = stats.lowContrast
    val fallback = when {
        failureStreak == 1 -> if (lowContrast) {
            ScanDecodeAttempt.LOW_CONTRAST
        } else {
            ScanDecodeAttempt.INVERTED
        }
        failureStreak == 2 -> ScanDecodeAttempt.INVERTED
        failureStreak == 3 -> if (lowContrast) ScanDecodeAttempt.LOW_CONTRAST else ScanDecodeAttempt.HARD
        failureStreak % 4 == 0 -> ScanDecodeAttempt.HARD_LOW_CONTRAST
        failureStreak % 4 == 1 -> ScanDecodeAttempt.INVERTED
        failureStreak % 4 == 2 -> if (lowContrast) ScanDecodeAttempt.LOW_CONTRAST else ScanDecodeAttempt.INVERTED
        failureStreak % 4 == 3 -> ScanDecodeAttempt.HARD
        else -> if (lowContrast) ScanDecodeAttempt.LOW_CONTRAST else null
    }
    if (fallback != null) attempts += fallback
    return ScanDecodePlan(attempts)
}

data class ScanFrameStats(
    val width: Int,
    val height: Int,
    val meanLuma: Float,
    val contrast: Float,
    val stdDev: Float,
    val darkFraction: Float,
    val brightFraction: Float,
    val sampledPixels: Int,
) {
    val valid: Boolean get() = width > 0 && height > 0 && sampledPixels > 0

    /**
     * A washed-out screen/glare frame. A normal black-on-white QR still has many dark
     * modules, so brightness alone must not drive exposure to the camera minimum.
     */
    val overexposed: Boolean
        get() = valid && (
            (brightFraction >= OVEREXPOSED_BRIGHT_FRACTION && darkFraction <= OVEREXPOSED_DARK_FRACTION) ||
                (meanLuma >= OVEREXPOSED_MEAN && contrast < OVEREXPOSED_CONTRAST)
            )

    /** A dark frame without meaningful highlight content; raising exposure may help. */
    val underexposed: Boolean
        get() = valid && meanLuma <= UNDEREXPOSED_MEAN && brightFraction <= UNDEREXPOSED_BRIGHT_FRACTION

    val lowContrast: Boolean get() = valid && contrast < LOW_CONTRAST_RANGE

    companion object {
        const val OVEREXPOSED_BRIGHT_FRACTION = 0.40f
        const val OVEREXPOSED_DARK_FRACTION = 0.06f
        const val OVEREXPOSED_MEAN = 220f
        const val OVEREXPOSED_CONTRAST = 70f
        const val UNDEREXPOSED_MEAN = 58f
        const val UNDEREXPOSED_BRIGHT_FRACTION = 0.04f
        const val LOW_CONTRAST_RANGE = 38f

        val EMPTY = ScanFrameStats(0, 0, 0f, 0f, 0f, 0f, 0f, 0)
    }
}

/** Samples the luminance plane without allocating a copy of the camera frame. */
object ScanFrameAnalyzer {
    fun analyze(data: ByteArray, width: Int, height: Int, targetSamples: Int = 192): ScanFrameStats {
        if (width <= 0 || height <= 0) return ScanFrameStats.EMPTY
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > data.size.toLong()) {
            return ScanFrameStats(width, height, 0f, 0f, 0f, 0f, 0f, 0)
        }

        val step = max(1, min(width, height) / max(16, targetSamples))
        val histogram = IntArray(256)
        var count = 0
        var sum = 0.0
        var sumSquares = 0.0
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                val luma = data[row + x].toInt() and 0xFF
                histogram[luma]++
                count++
                sum += luma
                sumSquares += luma.toDouble() * luma
                x += step
            }
            y += step
        }
        if (count == 0) return ScanFrameStats(width, height, 0f, 0f, 0f, 0f, 0f, 0)

        val mean = sum / count
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        val stdDev = sqrt(variance).toFloat()
        val p10 = percentile(histogram, count, 0.10f)
        val p90 = percentile(histogram, count, 0.90f)
        var dark = 0
        var bright = 0
        for (index in 0..31) dark += histogram[index]
        for (index in 224..255) bright += histogram[index]

        return ScanFrameStats(
            width = width,
            height = height,
            meanLuma = mean.toFloat(),
            contrast = (p90 - p10).toFloat(),
            stdDev = stdDev,
            darkFraction = dark.toFloat() / count,
            brightFraction = bright.toFloat() / count,
            sampledPixels = count,
        )
    }

    private fun percentile(histogram: IntArray, count: Int, fraction: Float): Int {
        val wanted = (count * fraction).toInt().coerceIn(0, count - 1)
        var seen = 0
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen > wanted) return value
        }
        return 255
    }
}

/** Source-relative normalized candidate point; (0,0) is the decoder crop's top-left. */
data class ScanPoint(val x: Float, val y: Float) {
    val usable: Boolean
        get() = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
}

data class ScanCodeGeometry(
    val validFinderTriple: Boolean,
    val spanFraction: Float,
) {
    companion object {
        val NONE = ScanCodeGeometry(false, 0f)
        const val MIN_CODE_SPAN = 0.18f
        const val LARGE_CODE_SPAN = 0.34f
    }
}

/**
 * A single possible-result-point is only a local finder candidate. Zoom is justified
 * only by three candidates whose pairwise distances form a plausible QR finder triangle
 * (two roughly equal legs and a hypotenuse close to sqrt(2) times a leg).
 */
fun estimateCodeGeometry(
    points: List<ScanPoint>,
    width: Int,
    height: Int,
): ScanCodeGeometry {
    if (width <= 0 || height <= 0) return ScanCodeGeometry.NONE
    val usable = points.asSequence().filter { it.usable }.take(10).toList()
    if (usable.size < 3) return ScanCodeGeometry.NONE

    var bestSpan = 0f
    for (a in 0 until usable.size - 2) {
        for (b in a + 1 until usable.size - 1) {
            for (c in b + 1 until usable.size) {
                val span = finderTriangleSpan(usable[a], usable[b], usable[c], width, height)
                if (span > bestSpan) bestSpan = span
            }
        }
    }
    return if (bestSpan > 0f) ScanCodeGeometry(true, bestSpan) else ScanCodeGeometry.NONE
}

private fun finderTriangleSpan(a: ScanPoint, b: ScanPoint, c: ScanPoint, width: Int, height: Int): Float {
    val distances = floatArrayOf(
        pixelDistance(a, b, width, height),
        pixelDistance(a, c, width, height),
        pixelDistance(b, c, width, height),
    )
    distances.sort()
    val leg = distances[0]
    val otherLeg = distances[1]
    val hypotenuse = distances[2]
    if (leg <= 0f || otherLeg <= 0f || hypotenuse <= 0f) return 0f
    val legsRatio = leg / otherLeg
    val hypotenuseRatio = hypotenuse / otherLeg
    if (legsRatio !in 0.70f..1.30f) return 0f
    if (hypotenuseRatio !in 1.20f..1.65f) return 0f
    return hypotenuse / min(width, height)
}

private fun pixelDistance(a: ScanPoint, b: ScanPoint, width: Int, height: Int): Float {
    val dx = (a.x - b.x) * width
    val dy = (a.y - b.y) * height
    return sqrt(dx * dx + dy * dy)
}

enum class ScanFocusMode { NONE, CONTINUOUS, AUTO, MACRO }

data class ScanCameraCapabilities(
    val exposureMinIndex: Int = 0,
    val exposureMaxIndex: Int = 0,
    val exposureStepEv: Float = 0f,
    val zoomSupported: Boolean = false,
    val maxZoomRatio: Float = 1f,
    val focusModes: Set<String> = emptySet(),
) {
    val exposureSupported: Boolean
        get() = scanExposureSetting(0f, exposureMinIndex, exposureMaxIndex, exposureStepEv) != null

    val canDarken: Boolean get() = exposureSupported && exposureMinIndex < 0

    val continuousFocusSupported: Boolean
        get() = "continuous-picture" in focusModes || "continuous-video" in focusModes

    val autoFocusSupported: Boolean get() = "auto" in focusModes
    val macroFocusSupported: Boolean get() = "macro" in focusModes

    /** Auto zoom is intentionally capped even when the driver exposes a larger range. */
    val maxUsefulZoomRatio: Float
        get() = if (zoomSupported) max(1f, min(maxZoomRatio, MAX_AUTO_ZOOM)) else 1f

    companion object {
        const val MAX_AUTO_ZOOM = 2.0f
        val NONE = ScanCameraCapabilities()
    }
}

data class ScanAutoConfig(
    val enabled: Boolean = true,
    val mode: ScanAssistMode = ScanAssistMode.NORMAL,
    val baseExposureEv: Float = 0f,
    val manualHoldUntilMs: Long = 0L,
)

enum class ScanAutoStatus { OFF, WAITING, STABLE, ADJUSTING, MANUAL, LIMIT, LIMITED, UNSUPPORTED }

data class ScanAutoAdvice(
    val exposureEv: Float? = null,
    val zoomRatio: Float? = null,
    val focusMode: ScanFocusMode? = null,
    val status: ScanAutoStatus = ScanAutoStatus.STABLE,
    val exposureAtLimit: Boolean = false,
    val zoomAtLimit: Boolean = false,
    val exposureUnsupported: Boolean = false,
    val zoomUnsupported: Boolean = false,
)

/**
 * Generation gate for asynchronous decoder/camera work. A callback may only publish
 * while its captured token still matches the active preview generation.
 */
class ScanAssistSession {
    private val generation = AtomicInteger(0)

    @Volatile
    private var active = false

    val isActive: Boolean get() = active
    val currentToken: Int get() = generation.get()

    fun start(): Int {
        active = true
        return generation.incrementAndGet()
    }

    fun stop() {
        active = false
        generation.incrementAndGet()
    }

    fun isActive(token: Int): Boolean = active && token == generation.get()
}

internal fun exposureStepFor(caps: ScanCameraCapabilities): Float =
    max(0.25f, caps.exposureStepEv).coerceAtMost(1f)

internal fun moveExposureToward(current: Float, target: Float, step: Float): Float = when {
    abs(target - current) <= step / 2f -> target
    current < target -> min(target, current + step)
    else -> max(target, current - step)
}
