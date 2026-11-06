package dev.rain.laundry

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.DecoderFactory
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ScanOptions
import dev.rain.laundry.scanner.ScreenCodeCaptureActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Runs directly in the scanner on a dedicated emulator, without an account or business API. */
@RunWith(AndroidJUnit4::class)
class ScannerRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun grantCameraPermission() {
        val command = "pm grant ${context.packageName} ${Manifest.permission.CAMERA}"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
    }

    @Test fun screenModeSurvivesRecreationAndRotationAndNormalModeRestoresExposure() {
        launchScanner().use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            awaitPreview(scenario)
            assertMode(scenario, screen = false)
            val initialFrame = AtomicReference<Rect>()
            scenario.onActivity { activity ->
                val frame = activity.findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner)
                    .barcodeView.previewFramingRect
                assertNotNull(frame)
                initialFrame.set(Rect(frame))
            }
            capture("scanner-01-normal-portrait")

            scenario.onActivity { activity ->
                activity.findViewById<RadioButton>(R.id.scanner_screen_mode).performClick()
            }
            awaitScreenHint(scenario)
            scenario.onActivity { activity ->
                // A supported camera can adjust this value; unsupported cameras keep a usable mode.
                val slider = activity.findViewById<SeekBar>(R.id.scanner_brightness)
                if (slider.isEnabled) assertTrue(slider.performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 8f) },
                ))
            }
            awaitScreenHint(scenario)
            val selectedProgress = AtomicReference<Int>()
            scenario.onActivity { selectedProgress.set(it.findViewById<SeekBar>(R.id.scanner_brightness).progress) }
            assertMode(scenario, screen = true, progress = selectedProgress.get())
            scenario.onActivity { activity ->
                assertEquals("Mode changes must preserve the decoder's preview crop", initialFrame.get(),
                    activity.findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner)
                        .barcodeView.previewFramingRect)
            }
            assertExposureSupportIsReported(scenario)
            capture("scanner-02-screen-portrait")

            scenario.recreate()
            awaitPreview(scenario)
            awaitScreenHint(scenario)
            assertMode(scenario, screen = true, progress = selectedProgress.get())

            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            awaitPreview(scenario)
            awaitScreenHint(scenario)
            assertMode(scenario, screen = true, progress = selectedProgress.get())
            capture("scanner-03-screen-landscape")

            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            awaitPreview(scenario)
            awaitScreenHint(scenario)
            assertMode(scenario, screen = true, progress = selectedProgress.get())

            scenario.onActivity { it.findViewById<RadioButton>(R.id.scanner_normal_mode).performClick() }
            await {
                onScanner(scenario) { activity ->
                    activity.findViewById<TextView>(R.id.scanner_hint).text.toString() ==
                        activity.getString(R.string.scanner_normal_hint)
                }
            }
            assertMode(scenario, screen = false)
            assertEquals("Normal mode restores automatic exposure compensation", 0, readExposure(scenario).current)
            capture("scanner-04-restored-normal")
            scenario.onActivity { it.findViewById<View>(R.id.scanner_close).performClick() }
            await { scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }

    @Test fun systemBackCancelsWithoutReturningADeviceCode() {
        launchScanner().use { scenario ->
            awaitPreview(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertNull(scenario.result.resultData)
        }
    }

    @Test fun configuredDecoderReadsSyntheticQrAtAllRotationsAndBothPolarities() {
        launchScanner().use { scenario ->
            val factory = AtomicReference<DecoderFactory>()
            scenario.onActivity { activity ->
                factory.set(activity.findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).decoderFactory)
            }
            val payload = "https://example.invalid/scanner-regression?fixture=synthetic"
            val matrix = MultiFormatWriter().encode(
                payload, BarcodeFormat.QR_CODE, 320, 320, mapOf(EncodeHintType.MARGIN to 4),
            )
            for (inverted in listOf(false, true)) {
                var pixels = pixels(matrix, inverted)
                for (rotation in 0..3) {
                    val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
                    val decoder = factory.get().createDecoder(emptyMap<DecodeHintType, Any>())
                    // MixedDecoder alternates polarity between frames; use the same generated frame twice.
                    val result = decoder.decode(source) ?: decoder.decode(source)
                    assertNotNull("Synthetic QR failed: rotation=${rotation * 90}, inverted=$inverted", result)
                    assertEquals(payload, result!!.text)
                    pixels = rotateClockwise(pixels, matrix.width)
                }
            }
            val barcode = MultiFormatWriter().encode("SCANNER-TEST-CODE128", BarcodeFormat.CODE_128, 640, 160)
            val source = RGBLuminanceSource(barcode.width, barcode.height, pixels(barcode, false))
            val decoder = factory.get().createDecoder(emptyMap<DecodeHintType, Any>())
            repeat(2) { assertNull("The device scanner remains restricted to QR codes", decoder.decode(source)) }
        }
    }

    private fun launchScanner(): ActivityScenario<ScreenCodeCaptureActivity> {
        val intent = ScanOptions().setCaptureActivity(ScreenCodeCaptureActivity::class.java)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(false)
            .setBeepEnabled(false).createScanIntent(context)
        return ActivityScenario.launchActivityForResult(intent)
    }

    private fun assertMode(scenario: ActivityScenario<ScreenCodeCaptureActivity>, screen: Boolean, progress: Int? = null) {
        scenario.onActivity { activity ->
            assertEquals(screen, activity.findViewById<RadioButton>(R.id.scanner_screen_mode).isChecked)
            assertEquals(!screen, activity.findViewById<RadioButton>(R.id.scanner_normal_mode).isChecked)
            assertEquals(if (screen) View.VISIBLE else View.INVISIBLE,
                activity.findViewById<View>(R.id.scanner_brightness_controls).visibility)
            if (progress != null) assertEquals(progress, activity.findViewById<SeekBar>(R.id.scanner_brightness).progress)
            assertTrue(activity.findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).barcodeView.isPreviewActive)
        }
    }

    private fun assertExposureSupportIsReported(scenario: ActivityScenario<ScreenCodeCaptureActivity>) {
        val exposure = readExposure(scenario)
        scenario.onActivity { activity ->
            val text = activity.findViewById<TextView>(R.id.scanner_hint).text.toString()
            if (exposure.minimum >= 0 || exposure.step <= 0f) {
                assertEquals(activity.getString(R.string.scanner_unsupported_hint), text)
                assertFalse(activity.findViewById<SeekBar>(R.id.scanner_brightness).isEnabled)
            } else {
                assertTrue(listOf(R.string.scanner_screen_hint, R.string.scanner_limit_hint,
                    R.string.scanner_rejected_hint).any { text == activity.getString(it) })
            }
            assertTrue("An unsupported adjustment must not prevent mode selection",
                activity.findViewById<RadioButton>(R.id.scanner_normal_mode).isEnabled)
        }
    }

    private data class Exposure(val minimum: Int, val step: Float, val current: Int)

    private fun readExposure(scenario: ActivityScenario<ScreenCodeCaptureActivity>): Exposure {
        val result = AtomicReference<Exposure>()
        scenario.onActivity { activity ->
            activity.findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).changeCameraParameters { parameters ->
                result.set(Exposure(parameters.minExposureCompensation, parameters.exposureCompensationStep,
                    parameters.exposureCompensation))
                parameters
            }
        }
        await { result.get() != null }
        return result.get()
    }

    private fun awaitPreview(scenario: ActivityScenario<ScreenCodeCaptureActivity>) = await {
        onScanner(scenario) { it.findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).barcodeView.isPreviewActive }
    }

    private fun awaitScreenHint(scenario: ActivityScenario<ScreenCodeCaptureActivity>) = await {
        onScanner(scenario) { activity ->
            val text = activity.findViewById<TextView>(R.id.scanner_hint).text.toString()
            listOf(R.string.scanner_screen_hint, R.string.scanner_limit_hint,
                R.string.scanner_unsupported_hint, R.string.scanner_rejected_hint)
                .any { text == activity.getString(it) }
        }
    }

    private fun awaitOrientation(scenario: ActivityScenario<ScreenCodeCaptureActivity>, orientation: Int) = await {
        onScanner(scenario) { it.resources.configuration.orientation == orientation }
    }

    private fun onScanner(scenario: ActivityScenario<ScreenCodeCaptureActivity>, check: (ScreenCodeCaptureActivity) -> Boolean): Boolean {
        val result = AtomicReference(false)
        scenario.onActivity { result.set(check(it)) }
        return result.get()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        fail("Timed out waiting for scanner UI or camera parameters")
    }

    private fun pixels(matrix: BitMatrix, inverted: Boolean): IntArray = IntArray(matrix.width * matrix.height) { index ->
        val dark = matrix[index % matrix.width, index / matrix.width] xor inverted
        if (dark) 0xFF586E88.toInt() else 0xFFE3EFFB.toInt()
    }

    private fun rotateClockwise(pixels: IntArray, size: Int): IntArray = IntArray(pixels.size) { index ->
        val x = index % size
        val y = index / size
        pixels[(size - 1 - x) * size + y]
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Main-thread idleness does not wait for SurfaceFlinger's rotation animation.
        SystemClock.sleep(700)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "runtime-checks").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
