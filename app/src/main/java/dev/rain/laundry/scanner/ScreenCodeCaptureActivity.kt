package dev.rain.laundry.scanner

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import dev.rain.laundry.R
import dev.rain.laundry.core.scanExposureSetting

/** Keeps ZXing's permission, result delivery and camera lifecycle in CaptureActivity. */
class ScreenCodeCaptureActivity : CaptureActivity() {
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var modes: RadioGroup
    private lateinit var brightness: SeekBar
    private lateinit var brightnessControls: View
    private lateinit var hint: TextView
    private var screenMode = false
    private var dimmingProgress = DEFAULT_DIMMING_PROGRESS
    private var canDarken: Boolean? = null
    private var parameterRequest = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        screenMode = savedInstanceState?.getBoolean(STATE_SCREEN_MODE) ?: false
        dimmingProgress = (savedInstanceState?.getInt(STATE_DIMMING_PROGRESS)
            ?: DEFAULT_DIMMING_PROGRESS).coerceIn(0, MAX_DIMMING_PROGRESS)
        super.onCreate(savedInstanceState)

        // CaptureManager initializes these from the intent during super.onCreate(). Configure after it.
        scanner.cameraSettings.apply {
            isContinuousFocusEnabled = true
            isAutoTorchEnabled = false
        }
        scanner.decoderFactory = DefaultDecoderFactory(
            listOf(BarcodeFormat.QR_CODE),
            mapOf(DecodeHintType.TRY_HARDER to true),
            null,
            2, // ZXing Embedded 4.3.0: MixedDecoder alternates normal and inverted frames.
        )
        // The instruction is outside the preview so it never covers a code near its lower edge.
        scanner.statusView?.visibility = View.GONE

        modes.check(if (screenMode) R.id.scanner_screen_mode else R.id.scanner_normal_mode)
        brightness.progress = dimmingProgress
        updateControls()
        findViewById<View>(R.id.scanner_close).setOnClickListener { finish() }
        modes.setOnCheckedChangeListener { _, checkedId ->
            screenMode = checkedId == R.id.scanner_screen_mode
            updateControls()
            applyExposure()
        }
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                dimmingProgress = progress
                if (fromUser) applyExposure()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        scanner.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() {
                canDarken = null
                updateControls()
                applyExposure()
            }
            override fun previewStopped() {
                parameterRequest++ // Discard camera-thread replies belonging to a closed preview.
                brightness.isEnabled = false
            }
            override fun cameraError(error: Exception) {
                parameterRequest++
                brightness.isEnabled = false
                hint.setText(R.string.scanner_camera_error)
            }
            override fun cameraClosed() = Unit
        })
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_screen_code_capture)
        scanner = findViewById(R.id.zxing_barcode_scanner)
        modes = findViewById(R.id.scanner_modes)
        brightness = findViewById(R.id.scanner_brightness)
        brightnessControls = findViewById(R.id.scanner_brightness_controls)
        hint = findViewById(R.id.scanner_hint)

        // Android 15 enforces edge-to-edge for target 35. Keep controls outside cutouts/navigation.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.scanner_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        return scanner
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SCREEN_MODE, screenMode)
        outState.putInt(STATE_DIMMING_PROGRESS, dimmingProgress)
        super.onSaveInstanceState(outState)
    }

    private fun updateControls() {
        // Keep the preview size stable: ZXing captures its decode crop when preview starts.
        brightnessControls.visibility = if (screenMode) View.VISIBLE else View.INVISIBLE
        brightness.isEnabled = screenMode && canDarken != false && scanner.barcodeView.isPreviewActive
    }

    private fun applyExposure() {
        val preview = scanner.barcodeView
        if (!preview.isPreviewActive) return
        val request = ++parameterRequest
        val targetEv = if (screenMode) -(dimmingProgress + 1) * EV_PER_SLIDER_STEP else 0f

        // Both callbacks run, in order, on ZXing's camera thread. The second reads the driver's
        // actual parameters after the first setParameters call, including a driver rejection.
        preview.changeCameraParameters { parameters ->
            val setting = scanExposureSetting(
                targetEv, parameters.minExposureCompensation,
                parameters.maxExposureCompensation, parameters.exposureCompensationStep,
            )
            if (setting != null) {
                try {
                    parameters.exposureCompensation = setting.index
                } catch (_: RuntimeException) {
                    // Some camera drivers expose a range but reject changes; readback below reports it.
                }
            }
            parameters
        }
        preview.changeCameraParameters { parameters ->
            val expected = scanExposureSetting(
                targetEv, parameters.minExposureCompensation,
                parameters.maxExposureCompensation, parameters.exposureCompensationStep,
            )
            val actualIndex = parameters.exposureCompensation
            val minimumIndex = parameters.minExposureCompensation
            runOnUiThread {
                if (request == parameterRequest && preview.isPreviewActive && !isFinishing && !isDestroyed) {
                    canDarken = expected?.canDarken == true
                    updateControls()
                    hint.setText(when {
                        expected != null && actualIndex != expected.index -> R.string.scanner_rejected_hint
                        !screenMode -> R.string.scanner_normal_hint
                        expected == null || !expected.canDarken -> R.string.scanner_unsupported_hint
                        expected.actualEv > targetEv + 0.01f && actualIndex == minimumIndex ->
                            R.string.scanner_limit_hint
                        else -> R.string.scanner_screen_hint
                    })
                }
            }
            parameters
        }
    }

    private companion object {
        const val STATE_SCREEN_MODE = "screen_code_mode"
        const val STATE_DIMMING_PROGRESS = "screen_code_dimming"
        const val DEFAULT_DIMMING_PROGRESS = 5 // -1.5 EV, with 0.25 EV per slider position.
        const val MAX_DIMMING_PROGRESS = 11 // -3 EV, clamped again to the camera's actual range.
        const val EV_PER_SLIDER_STEP = 0.25f
    }
}
