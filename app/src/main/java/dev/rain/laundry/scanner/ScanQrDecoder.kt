package dev.rain.laundry.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory
import dev.rain.laundry.core.ScanAssistSession
import dev.rain.laundry.core.ScanDecodeAttempt
import dev.rain.laundry.core.ScanFrameAnalyzer
import dev.rain.laundry.core.ScanFrameObservation
import dev.rain.laundry.core.ScanFrameStats
import dev.rain.laundry.core.ScanPoint
import dev.rain.laundry.core.ScanPreviewBinding
import dev.rain.laundry.core.planScanDecode
import kotlin.math.min

/**
 * QR-only decoder with an explicit fast path.
 *
 * - Every frame first tries normal polarity with HybridBinarizer and no TRY_HARDER.
 * - A failure adds at most one bounded fallback: inverted Hybrid, global-histogram for
 *   low contrast, or a periodic TRY_HARDER/global-histogram combination for difficult screens.
 * - The reader, candidate list and reusable inversion buffer belong to the decoder
 *   thread, so no mutable ZXing state is touched from the UI thread.
 *
 * This class is intentionally Android-free so the same policy is covered by JVM tests.
 */
class ScanQrDecoder(
    hints: Map<DecodeHintType, *>,
    private val observer: (ScanFrameObservation) -> Unit = {},
    private val session: ScanAssistSession = ScanAssistSession(),
    private val sessionToken: Int = session.start(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Decoder(QRCodeReader()) {
    private val reader = QRCodeReader()
    private val baseHints = HashMap<DecodeHintType, Any>()
    private val hardHints: Map<DecodeHintType, Any>
    private val candidates = ArrayList<ScanPoint>(8)
    private val possiblePoints = ArrayList<ResultPoint>(8)
    private val inversion = ByteBufferHolder()
    private var frameWidth = 0
    private var frameHeight = 0
    private var failureStreak = 0
    private var frameIndex = 0L

    init {
        hints.forEach { (key, value) ->
            if (value != null) baseHints[key] = value
        }
        // containsKey(TRY_HARDER) is what ZXing checks, so false is not a fast path.
        baseHints.remove(DecodeHintType.TRY_HARDER)
        baseHints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hardHints = HashMap(baseHints).apply { put(DecodeHintType.TRY_HARDER, true) }
    }

    override fun decode(source: LuminanceSource): Result? {
        frameIndex++
        frameWidth = source.width
        frameHeight = source.height
        candidates.clear()
        possiblePoints.clear()
        if (!isCurrent()) return null
        val startedAt = clock()
        val stats = try {
            ScanFrameAnalyzer.analyze(source.matrix, source.width, source.height)
        } catch (_: RuntimeException) {
            ScanFrameStats.EMPTY
        }
        val plan = planScanDecode(stats, failureStreak)
        val attempts = ArrayList<ScanDecodeAttempt>(plan.attempts.size)
        var result: Result? = null

        for (attempt in plan.attempts) {
            if (!isCurrent()) break
            attempts += attempt
            result = when (attempt) {
                ScanDecodeAttempt.FAST -> tryDecode(source, globalHistogram = false, hints = baseHints)
                ScanDecodeAttempt.INVERTED ->
                    tryDecode(inverted(source), globalHistogram = false, hints = baseHints)
                ScanDecodeAttempt.LOW_CONTRAST ->
                    tryDecode(source, globalHistogram = true, hints = baseHints)
                ScanDecodeAttempt.HARD -> tryDecode(source, globalHistogram = false, hints = hardHints)
                ScanDecodeAttempt.HARD_LOW_CONTRAST ->
                    tryDecode(source, globalHistogram = true, hints = hardHints)
            }
            if (result != null) break
        }

        // A stopped/moved-on preview must not publish a result, statistics or points.
        if (!isCurrent()) {
            candidates.clear()
            possiblePoints.clear()
            return null
        }
        if (result != null) {
            failureStreak = 0
        } else {
            failureStreak++
        }

        val elapsed = (clock() - startedAt).coerceAtLeast(0L)
        try {
            observer(
                ScanFrameObservation(
                    frameIndex = frameIndex,
                    timestampMs = clock(),
                    stats = stats,
                    candidates = candidates.toList(),
                    attempts = attempts,
                    decoded = result != null,
                    decodeMillis = elapsed,
                ),
            )
        } catch (_: RuntimeException) {
            // A UI/session callback must never take down ZXing's decoder thread.
        }
        if (!isCurrent()) {
            candidates.clear()
            possiblePoints.clear()
            return null
        }
        return result
    }

    /** Exposed for the factory/session regression tests; false suppresses old results. */
    fun isCurrent(): Boolean = session.isActive(sessionToken)

    override fun foundPossibleResultPoint(point: ResultPoint) {
        if (!isCurrent()) return
        if (possiblePoints.size < MAX_CANDIDATES) possiblePoints += point
        if (frameWidth <= 0 || frameHeight <= 0 || candidates.size >= MAX_CANDIDATES) return
        val x = point.x / frameWidth
        val y = point.y / frameHeight
        if (x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {
            candidates += ScanPoint(x, y)
        }
    }

    override fun getPossibleResultPoints(): List<ResultPoint> =
        if (isCurrent()) ArrayList(possiblePoints) else emptyList()

    private fun tryDecode(
        source: LuminanceSource,
        globalHistogram: Boolean,
        hints: Map<DecodeHintType, Any>,
    ): Result? {
        val binarizer = if (globalHistogram) {
            GlobalHistogramBinarizer(source)
        } else {
            HybridBinarizer(source)
        }
        return try {
            reader.decode(BinaryBitmap(binarizer), hints)
        } catch (_: ReaderException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun inverted(source: LuminanceSource): LuminanceSource = InvertedSource(source, inversion)

    private class ByteBufferHolder {
        var bytes: ByteArray = ByteArray(0)
    }

    /** Inverts into a reused buffer instead of allocating a full frame on every fallback. */
    private class InvertedSource(
        private val delegate: LuminanceSource,
        private val holder: ByteBufferHolder,
    ) : LuminanceSource(delegate.width, delegate.height) {
        override fun getRow(y: Int, row: ByteArray): ByteArray {
            val result = delegate.getRow(y, row)
            val count = min(result.size, width)
            for (index in 0 until count) {
                result[index] = (result[index].toInt() xor 0xFF).toByte()
            }
            return result
        }

        override fun getMatrix(): ByteArray {
            val needed = width * height
            if (holder.bytes.size != needed) holder.bytes = ByteArray(needed)
            val source = delegate.matrix
            val count = min(source.size, needed)
            for (index in 0 until count) {
                holder.bytes[index] = (source[index].toInt() xor 0xFF).toByte()
            }
            return holder.bytes
        }

        override fun isCropSupported(): Boolean = delegate.isCropSupported

        override fun crop(left: Int, top: Int, width: Int, height: Int): LuminanceSource =
            InvertedSource(delegate.crop(left, top, width, height), holder)

        override fun isRotateSupported(): Boolean = delegate.isRotateSupported
    }

    private companion object {
        const val MAX_CANDIDATES = 16
    }
}

class ScanQrDecoderFactory(
    private val onFrame: (ScanPreviewBinding, ScanFrameObservation) -> Unit,
    private val newSession: () -> ScanPreviewBinding,
    private val clock: () -> Long = System::currentTimeMillis,
) : DecoderFactory {
    override fun createDecoder(hints: Map<DecodeHintType, *>): Decoder {
        val binding = newSession()
        return ScanQrDecoder(
            hints = hints,
            observer = { observation -> onFrame(binding, observation) },
            clock = clock,
            session = binding.session,
            sessionToken = binding.token,
        )
    }
}
