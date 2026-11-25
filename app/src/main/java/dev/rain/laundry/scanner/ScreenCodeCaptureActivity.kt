package dev.rain.laundry.scanner

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import dev.rain.laundry.R
import dev.rain.laundry.core.ScanAssistMode
import dev.rain.laundry.core.ScanAutoAdvice
import dev.rain.laundry.core.ScanAutoConfig
import dev.rain.laundry.core.ScanAutoStatus
import dev.rain.laundry.core.scanExposureSetting

/** Keeps ZXing's permission, result delivery and camera lifecycle in CaptureActivity. */
class ScreenCodeCaptureActivity : CaptureActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.rain.laundry.i18n.LanguagePreferences.wrap(newBase))
    }

    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var modes: RadioGroup
    private lateinit var brightness: SeekBar
    private lateinit var brightnessControls: View
    private lateinit var hint: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var autoStatus: TextView
    private lateinit var controller: ScanAssistController
    private var screenMode = false
    private var dimmingProgress = DEFAULT_DIMMING_PROGRESS
    private var autoEnabled = true
    private var manualHoldUntilMs = 0L
    private var canDarken: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        screenMode = savedInstanceState?.getBoolean(STATE_SCREEN_MODE) ?: false
        dimmingProgress = (savedInstanceState?.getInt(STATE_DIMMING_PROGRESS)
            ?: DEFAULT_DIMMING_PROGRESS).coerceIn(0, MAX_DIMMING_PROGRESS)
        autoEnabled = savedInstanceState?.getBoolean(STATE_AUTO_ENABLED) ?: true
        val restoredManualHold = savedInstanceState?.getBoolean(STATE_MANUAL_HOLD) ?: false
        super.onCreate(savedInstanceState)
        if (!ScannerFeature.isEnabled(this)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // CaptureManager initializes these from the intent during super.onCreate(). Configure after it.
        scanner.cameraSettings.apply {
            isContinuousFocusEnabled = true
            isAutoTorchEnabled = false
        }
        // The instruction is outside the preview so it never covers a code near its lower edge.
        scanner.statusView?.visibility = View.GONE

        controller = ScanAssistController(
            previewProvider = { scanner.barcodeView },
            onCapabilities = { capabilities, userInitiated ->
                canDarken = capabilities.canDarken
                updateControls()
                if (userInitiated && screenMode) {
                    hint.setText(
                        if (!capabilities.exposureSupported || !capabilities.canDarken) {
                            R.string.scanner_unsupported_hint
                        } else {
                            R.string.scanner_screen_hint
                        },
                    )
                }
            },
            onReadback = ::renderExposureReadback,
            onAdvice = ::renderAutoAdvice,
        )
        scanner.decoderFactory = ScanQrDecoderFactory(
            onFrame = controller::onDecoderFrame,
            newSession = { controller.newPreviewBinding() },
            clock = SystemClock::elapsedRealtime,
        )

        modes.check(if (screenMode) R.id.scanner_screen_mode else R.id.scanner_normal_mode)
        brightness.progress = dimmingProgress
        autoSwitch.isChecked = autoEnabled
        if (autoEnabled && screenMode && restoredManualHold) {
            manualHoldUntilMs = SystemClock.elapsedRealtime() + MANUAL_HOLD_MS
        }
        updateControls()
        autoStatus.setText(if (autoEnabled) R.string.scanner_auto_waiting else R.string.scanner_auto_off)

        findViewById<View>(R.id.scanner_close).setOnClickListener { finish() }
        modes.setOnCheckedChangeListener { _, checkedId ->
            val nextScreen = checkedId == R.id.scanner_screen_mode
            if (nextScreen == screenMode) return@setOnCheckedChangeListener
            screenMode = nextScreen
            manualHoldUntilMs = 0L
            updateControls()
            autoStatus.setText(if (autoEnabled) R.string.scanner_auto_waiting else R.string.scanner_auto_off)
            if (!screenMode) hint.setText(R.string.scanner_normal_hint)
            controller.setMode(mode(), baseExposureEv())
        }
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                dimmingProgress = progress
                if (!fromUser) return
                manualHoldUntilMs = SystemClock.elapsedRealtime() + MANUAL_HOLD_MS
                controller.setManualExposure(baseExposureEv(), manualHoldUntilMs)
                autoStatus.setText(
                    if (autoEnabled) R.string.scanner_auto_manual else R.string.scanner_auto_off,
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        autoSwitch.setOnCheckedChangeListener { _, enabled ->
            autoEnabled = enabled
            manualHoldUntilMs = 0L
            controller.setAutoEnabled(enabled)
            autoStatus.setText(if (enabled) R.string.scanner_auto_waiting else R.string.scanner_auto_off)
        }
        scanner.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() {
                canDarken = null
                updateControls()
                autoStatus.setText(if (autoEnabled) R.string.scanner_auto_waiting else R.string.scanner_auto_off)
                controller.onPreviewStarted()
            }

            override fun previewStopped() {
                controller.onPreviewStopped()
                brightness.isEnabled = false
            }

            override fun cameraError(error: Exception) {
                controller.onPreviewStopped()
                brightness.isEnabled = false
                hint.setText(R.string.scanner_camera_error)
            }

            override fun cameraClosed() = Unit
        })
        controller.prepare(config())
    }

    override fun onResume() {
        if (::controller.isInitialized) controller.prepareIfNeeded(config())
        super.onResume()
    }

    override fun onPause() {
        if (::controller.isInitialized) controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.stop()
        super.onDestroy()
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_screen_code_capture)
        scanner = findViewById(R.id.zxing_barcode_scanner)
        modes = findViewById(R.id.scanner_modes)
        brightness = findViewById(R.id.scanner_brightness)
        brightnessControls = findViewById(R.id.scanner_brightness_controls)
        hint = findViewById(R.id.scanner_hint)
        autoSwitch = findViewById(R.id.scanner_auto)
        autoStatus = findViewById(R.id.scanner_auto_status)

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
        outState.putBoolean(STATE_AUTO_ENABLED, autoEnabled)
        outState.putBoolean(
            STATE_MANUAL_HOLD,
            autoEnabled && screenMode && SystemClock.elapsedRealtime() < manualHoldUntilMs,
        )
        super.onSaveInstanceState(outState)
    }

    private fun updateControls() {
        // Keep the preview size stable: ZXing captures its decode crop when preview starts.
        // Auto controls and hints have fixed heights, so state text cannot resize the preview.
        brightnessControls.visibility = if (screenMode) View.VISIBLE else View.INVISIBLE
        brightness.isEnabled = screenMode && canDarken != false && scanner.barcodeView.isPreviewActive
    }

    private fun renderExposureReadback(readback: ScanCameraReadback) {
        canDarken = readback.capabilities.canDarken
        updateControls()
        if (!readback.userInitiated) return
        if (!screenMode) {
            hint.setText(R.string.scanner_normal_hint)
            return
        }

        val requested = readback.requestedExposureEv ?: baseExposureEv()
        val expected = scanExposureSetting(
            requested,
            readback.capabilities.exposureMinIndex,
            readback.capabilities.exposureMaxIndex,
            readback.capabilities.exposureStepEv,
        )
        hint.setText(
            when {
                expected == null || !expected.canDarken -> R.string.scanner_unsupported_hint
                readback.actualExposureIndex != expected.index -> R.string.scanner_rejected_hint
                expected.actualEv > requested + 0.01f &&
                    readback.actualExposureIndex == readback.capabilities.exposureMinIndex ->
                    R.string.scanner_limit_hint
                else -> R.string.scanner_screen_hint
            },
        )
    }

    private fun renderAutoAdvice(advice: ScanAutoAdvice) {
        if (isFinishing || isDestroyed) return
        autoStatus.setText(
            when (advice.status) {
                ScanAutoStatus.OFF -> R.string.scanner_auto_off
                ScanAutoStatus.WAITING -> R.string.scanner_auto_waiting
                ScanAutoStatus.STABLE -> R.string.scanner_auto_stable
                ScanAutoStatus.ADJUSTING -> R.string.scanner_auto_adjusting
                ScanAutoStatus.MANUAL -> R.string.scanner_auto_manual
                ScanAutoStatus.LIMIT -> R.string.scanner_auto_limit
                ScanAutoStatus.LIMITED -> R.string.scanner_auto_limited
                ScanAutoStatus.UNSUPPORTED -> R.string.scanner_auto_unsupported
            },
        )
    }

    private fun mode(): ScanAssistMode =
        if (screenMode) ScanAssistMode.SCREEN else ScanAssistMode.NORMAL

    private fun baseExposureEv(): Float =
        if (screenMode) -(dimmingProgress + 1) * EV_PER_SLIDER_STEP else 0f

    private fun config(): ScanAutoConfig = ScanAutoConfig(
        enabled = autoEnabled,
        mode = mode(),
        baseExposureEv = baseExposureEv(),
        manualHoldUntilMs = if (autoEnabled) manualHoldUntilMs else 0L,
    )

    private companion object {
        const val STATE_SCREEN_MODE = "screen_code_mode"
        const val STATE_DIMMING_PROGRESS = "screen_code_dimming"
        const val STATE_AUTO_ENABLED = "screen_code_auto_enabled"
        const val STATE_MANUAL_HOLD = "screen_code_manual_hold"
        const val DEFAULT_DIMMING_PROGRESS = 5 // -1.5 EV, with 0.25 EV per slider position.
        const val MAX_DIMMING_PROGRESS = 11 // -3 EV, clamped again to the camera's actual range.
        const val EV_PER_SLIDER_STEP = 0.25f
        const val MANUAL_HOLD_MS = 6_000L
    }
}
