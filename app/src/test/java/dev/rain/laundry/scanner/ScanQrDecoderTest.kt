package dev.rain.laundry.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultPointCallback
import dev.rain.laundry.core.ScanAssistSession
import dev.rain.laundry.core.ScanDecodeAttempt
import dev.rain.laundry.core.ScanFrameObservation
import dev.rain.laundry.core.ScanPreviewBinding
import dev.rain.laundry.core.ScanPreviewSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanQrDecoderTest {
    private val payload = "https://example.invalid/scanner-auto?fixture=synthetic"

    @Test fun stoppingDuringObservationCannotReturnThePreviouslyDecodedResult() {
        val session = ScanAssistSession()
        val token = session.start()
        val decoder = ScanQrDecoder(
            hints = emptyMap<DecodeHintType, Any?>(),
            observer = { session.stop() },
            session = session,
            sessionToken = token,
            clock = { 0L },
        )
        assertNull(decoder.decode(source(pixels(qrMatrix(320)))))
        assertTrue(decoder.getPossibleResultPoints().isEmpty())
    }

    @Test fun normalQrUsesOnlyTheFastAttempt() {
        val observations = mutableListOf<ScanFrameObservation>()
        val decoder = ScanQrDecoder(emptyMap<DecodeHintType, Any?>(), { observations.add(it) }) { 0L }
        val source = source(pixels(qrMatrix(320), dark = 0xFF243447.toInt(), light = 0xFFEAF3FB.toInt()))

        val result = decoder.decode(source)

        assertNotNull(result)
        assertEquals(payload, result!!.text)
        assertEquals(1, observations.size)
        assertEquals(listOf(ScanDecodeAttempt.FAST), observations[0].attempts)
        assertTrue(observations[0].decoded)
    }

    @Test fun invertedQrFallsBackOnTheNextFrameNotEveryFrame() {
        val observations = mutableListOf<ScanFrameObservation>()
        val decoder = ScanQrDecoder(emptyMap<DecodeHintType, Any?>(), { observations.add(it) }) { 0L }
        val source = source(
            pixels(qrMatrix(320), dark = 0xFFEAF3FB.toInt(), light = 0xFF243447.toInt()),
        )

        assertNull(decoder.decode(source))
        assertEquals(listOf(ScanDecodeAttempt.FAST), observations[0].attempts)
        val result = decoder.decode(source)
        assertNotNull(result)
        assertEquals(listOf(ScanDecodeAttempt.FAST, ScanDecodeAttempt.INVERTED), observations[1].attempts)
    }

    @Test fun lowContrastQrUsesGlobalHistogramFallback() {
        val observations = mutableListOf<ScanFrameObservation>()
        val decoder = ScanQrDecoder(emptyMap<DecodeHintType, Any?>(), { observations.add(it) }) { 0L }
        val source = source(
            pixels(qrMatrix(320), dark = 0xFF646464.toInt(), light = 0xFF7C7C7C.toInt()),
        )

        assertNull(decoder.decode(source))
        val result = decoder.decode(source)

        assertNotNull(result)
        assertTrue(observations[1].attempts.contains(ScanDecodeAttempt.LOW_CONTRAST))
    }

    @Test fun allFourSourceRotationsDecode() {
        var pixels = pixels(qrMatrix(320))
        for (rotation in 0..3) {
            val decoder = ScanQrDecoder(emptyMap<DecodeHintType, Any?>()) { 0L }
            val result = decoder.decode(source(pixels))
            assertNotNull("rotation=${rotation * 90}", result)
            assertEquals(payload, result!!.text)
            pixels = rotateClockwise(pixels, 320)
        }
    }

    @Test fun smallQrInsideALargeFrameStillDecodes() {
        val qr = qrMatrix(160)
        val qrPixels = pixels(qr, dark = 0xFF101010.toInt(), light = 0xFFF4F4F4.toInt())
        val frame = IntArray(640 * 640) { 0xFFF4F4F4.toInt() }
        val offset = 240
        for (y in 0 until 160) {
            for (x in 0 until 160) {
                frame[(offset + y) * 640 + offset + x] = qrPixels[y * 160 + x]
            }
        }
        val decoder = ScanQrDecoder(emptyMap<DecodeHintType, Any?>()) { 0L }
        val result = decoder.decode(source(frame, size = 640))
        assertNotNull(result)
        assertEquals(payload, result!!.text)
    }

    @Test fun nonQrFormatsRemainRejected() {
        val barcode = MultiFormatWriter().encode(
            "SCANNER-TEST-CODE128",
            BarcodeFormat.CODE_128,
            640,
            160,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val decoder = ScanQrDecoder(emptyMap<DecodeHintType, Any?>()) { 0L }
        repeat(4) {
            assertNull(decoder.decode(source(pixels(barcode))))
        }
    }

    @Test fun candidatePointsAreCollectedOnTheDecoderThread() {
        lateinit var decoder: ScanQrDecoder
        val observations = mutableListOf<ScanFrameObservation>()
        val callback = ResultPointCallback { point -> decoder.foundPossibleResultPoint(point) }
        decoder = ScanQrDecoder(
            mapOf(DecodeHintType.NEED_RESULT_POINT_CALLBACK to callback),
            { observations.add(it) },
        ) { 0L }

        val result = decoder.decode(source(pixels(qrMatrix(320))))
        assertNotNull(result)
        val candidates = observations.single().candidates
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test fun hardFallbacksAreThrottledAndIncludeGlobalBinarizer() {
        val observations = mutableListOf<ScanFrameObservation>()
        val decoder = ScanQrDecoder(emptyMap<DecodeHintType, Any?>(), { observations.add(it) }) { 0L }
        val barcode = MultiFormatWriter().encode(
            "SCANNER-TEST-NOT-QR",
            BarcodeFormat.CODE_128,
            640,
            160,
        )
        val source = source(pixels(barcode))
        repeat(3) { decoder.decode(source) }
        assertFalse(observations.any { it.attempts.contains(ScanDecodeAttempt.HARD) })
        assertFalse(observations.any { it.attempts.contains(ScanDecodeAttempt.HARD_LOW_CONTRAST) })
        repeat(3) { decoder.decode(source) }
        assertTrue(observations.any { it.attempts.contains(ScanDecodeAttempt.HARD) })
        assertTrue(observations.any { it.attempts.contains(ScanDecodeAttempt.HARD_LOW_CONTRAST) })
    }

    @Test fun stoppedSessionSuppressesResultObservationAndHistoryPoints() {
        val session = ScanAssistSession()
        val token = session.start()
        lateinit var decoder: ScanQrDecoder
        val observations = mutableListOf<ScanFrameObservation>()
        val callback = ResultPointCallback { point -> decoder.foundPossibleResultPoint(point) }
        decoder = ScanQrDecoder(
            hints = mapOf(DecodeHintType.NEED_RESULT_POINT_CALLBACK to callback),
            observer = { observations.add(it) },
            session = session,
            sessionToken = token,
            clock = { 0L },
        )
        val source = source(pixels(qrMatrix(320)))
        assertNotNull(decoder.decode(source))
        assertTrue(decoder.getPossibleResultPoints().isNotEmpty())
        val observedBeforeStop = observations.size

        session.stop()
        assertFalse(decoder.isCurrent())
        assertTrue(decoder.getPossibleResultPoints().isEmpty())
        assertNull(decoder.decode(source))
        assertEquals(observedBeforeStop, observations.size)
        assertTrue(decoder.getPossibleResultPoints().isEmpty())
    }

    @Test fun factoryCreatesNewSessionBeforePreviewStartedAndSuppressesOldDecoder() {
        val registry = ScanPreviewSessionRegistry()
        val observations = mutableListOf<Pair<ScanPreviewBinding, ScanFrameObservation>>()
        val factory = ScanQrDecoderFactory(
            onFrame = { binding, observation -> observations += binding to observation },
            newSession = { registry.newBinding() },
            clock = { 0L },
        )
        val first = factory.createDecoder(emptyMap<DecodeHintType, Any?>()) as ScanQrDecoder
        val firstBinding = requireNotNull(registry.activeBinding)
        assertTrue(first.isCurrent())
        assertTrue(registry.isCurrent(firstBinding))

        // ZXing fires previewStarted after createDecoder; no token replacement here.
        assertTrue(registry.isCurrent(firstBinding))

        val second = factory.createDecoder(emptyMap<DecodeHintType, Any?>()) as ScanQrDecoder
        val secondBinding = requireNotNull(registry.activeBinding)
        assertTrue(second.isCurrent())
        assertTrue(registry.isCurrent(secondBinding))
        assertFalse(first.isCurrent())
        assertFalse(registry.isCurrent(firstBinding))

        val source = source(pixels(qrMatrix(320)))
        val observedBeforeSecond = observations.size
        assertNull(first.decode(source))
        assertEquals(observedBeforeSecond, observations.size)

        val secondResult = second.decode(source)
        assertNotNull(secondResult)
        assertEquals(payload, secondResult!!.text)
        assertTrue(observations.isNotEmpty())
        assertEquals(secondBinding, observations.last().first)
    }

    private fun qrMatrix(size: Int) = MultiFormatWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 4),
    )

    private fun pixels(
        matrix: com.google.zxing.common.BitMatrix,
        dark: Int = 0xFF243447.toInt(),
        light: Int = 0xFFEAF3FB.toInt(),
    ): IntArray = IntArray(matrix.width * matrix.height) { index ->
        val x = index % matrix.width
        val y = index / matrix.width
        if (matrix[x, y]) dark else light
    }

    private fun source(pixels: IntArray, size: Int = 320): RGBLuminanceSource =
        RGBLuminanceSource(size, size, pixels)

    private fun rotateClockwise(pixels: IntArray, size: Int): IntArray = IntArray(pixels.size) { index ->
        val x = index % size
        val y = index / size
        pixels[(size - 1 - x) * size + y]
    }
}
