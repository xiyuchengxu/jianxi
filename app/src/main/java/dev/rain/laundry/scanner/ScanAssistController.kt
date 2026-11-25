package dev.rain.laundry.scanner

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.journeyapps.barcodescanner.BarcodeView
import dev.rain.laundry.core.ScanActionBuffer
import dev.rain.laundry.core.ScanAssistMode
import dev.rain.laundry.core.ScanAutoAdjuster
import dev.rain.laundry.core.ScanAutoAdvice
import dev.rain.laundry.core.ScanAutoConfig
import dev.rain.laundry.core.ScanCameraAction
import dev.rain.laundry.core.ScanCameraCapabilities
import dev.rain.laundry.core.ScanFrameObservation
import dev.rain.laundry.core.ScanPreviewBinding
import dev.rain.laundry.core.ScanPreviewSessionRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges decoder-thread observations to throttled UI suggestions and serializes every
 * camera parameter write onto ZXing's camera thread.
 *
 * Camera actions and UI status use separate delivery paths. Status is throttled, but an
 * exposure/zoom/focus action is buffered immediately and survives subsequent status-only
 * frames. Each decoder creation gets a new preview binding; stop/restart invalidates the
 * old binding before a new decoder can be created.
 */
internal class ScanAssistController(
    private val previewProvider: () -> BarcodeView,
    private val onCapabilities: (ScanCameraCapabilities, Boolean) -> Unit,
    private val onReadback: (ScanCameraReadback) -> Unit,
    private val onAdvice: (ScanAutoAdvice) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewSessions = ScanPreviewSessionRegistry()
    private val adjuster = ScanAutoAdjuster()
    private val prepared = AtomicBoolean(false)
    private val epoch = AtomicInteger(0)
    private val userEpoch = AtomicInteger(-1)
    private val applyQueued = AtomicBoolean(false)
    private val applyRequest = AtomicInteger(0)
    private val adviceQueued = AtomicBoolean(false)
    private val latestAdvice = AtomicReference<ScanAutoAdvice?>(null)
    private val actionBuffer = ScanActionBuffer()
    private val actionQueued = AtomicBoolean(false)
    private val actionRequest = AtomicInteger(0)

    @Volatile
    private var config = ScanAutoConfig()

    @Volatile
    private var target = ScanCameraTarget()

    @Volatile
    private var capabilities: ScanCameraCapabilities? = null

    private var lastAdvicePostMs = 0L

    /** Called by the decoder factory before the decoder is returned. */
    fun newPreviewBinding(): ScanPreviewBinding {
        val binding = previewSessions.newBinding()
        invalidatePreviewState()
        return binding
    }

    fun prepare(config: ScanAutoConfig) {
        if (!prepared.compareAndSet(false, true)) return
        this.config = config
        synchronized(adjuster) { adjuster.reset(config) }
        target = ScanCameraTarget(config.baseExposureEv, 1f, null)
        capabilities = null
        epoch.incrementAndGet()
        userEpoch.set(-1)
        applyRequest.incrementAndGet()
        applyQueued.set(false)
        adviceQueued.set(false)
        latestAdvice.set(null)
        lastAdvicePostMs = 0L
        invalidateActions()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun prepareIfNeeded(config: ScanAutoConfig) {
        if (!prepared.get()) prepare(config)
    }

    /** Called after ZXing has started the preview and the decoder thread with this binding. */
    fun onPreviewStarted() {
        if (!prepared.get()) prepare(config)
        if (previewSessions.activeBinding == null) return
        capabilities = null
        target = ScanCameraTarget(config.baseExposureEv, 1f, null)
        synchronized(adjuster) { adjuster.reset(config) }
        epoch.incrementAndGet()
        userEpoch.set(epoch.get())
        applyRequest.incrementAndGet()
        applyQueued.set(false)
        lastAdvicePostMs = 0L
        invalidateActions()
        enqueueApply()
    }

    /**
     * A real preview stop invalidates the decoder session immediately. Rotation creates a
     * new binding in the next factory.createDecoder() call, before ZXing's state callback.
     */
    fun onPreviewStopped() {
        previewSessions.stopCurrent()
        epoch.incrementAndGet()
        userEpoch.set(-1)
        mainHandler.removeCallbacksAndMessages(null)
        applyRequest.incrementAndGet()
        applyQueued.set(false)
        adviceQueued.set(false)
        latestAdvice.set(null)
        lastAdvicePostMs = 0L
        capabilities = null
        invalidateActions()
        synchronized(adjuster) { adjuster.reset(config) }
    }

    fun stop() {
        onPreviewStopped()
        prepared.set(false)
    }

    fun setMode(mode: ScanAssistMode, baseExposureEv: Float) {
        config = config.copy(mode = mode, baseExposureEv = baseExposureEv, manualHoldUntilMs = 0L)
        synchronized(adjuster) { adjuster.reset(config) }
        target = target.copy(exposureEv = baseExposureEv, zoomRatio = 1f)
        markUserChange()
    }

    fun setManualExposure(baseExposureEv: Float, holdUntilMs: Long) {
        config = config.copy(baseExposureEv = baseExposureEv, manualHoldUntilMs = holdUntilMs)
        // Invalidating pending actions also resets adjuster state that they would have
        // advanced; keep exposure/zoom target and policy state consistent.
        synchronized(adjuster) { adjuster.reset(config) }
        target = target.copy(exposureEv = baseExposureEv, zoomRatio = 1f)
        markUserChange()
    }

    fun setAutoEnabled(enabled: Boolean) {
        config = config.copy(
            enabled = enabled,
            manualHoldUntilMs = if (enabled) 0L else config.manualHoldUntilMs,
        )
        synchronized(adjuster) { adjuster.reset(config) }
        target = target.copy(exposureEv = config.baseExposureEv, zoomRatio = 1f)
        markUserChange()
    }

    fun onDecoderFrame(binding: ScanPreviewBinding, observation: ScanFrameObservation) {
        if (!previewSessions.isCurrent(binding)) return
        val caps = capabilities ?: return
        val epochAtStart = epoch.get()
        val configAtStart = config
        val actionGenerationAtStart = actionBuffer.generation()
        val advice = synchronized(adjuster) {
            adjuster.observe(observation, configAtStart, caps, observation.timestampMs)
        }
        if (epochAtStart != epoch.get() ||
            configAtStart != config ||
            !previewSessions.isCurrent(binding)
        ) {
            return
        }

        // Camera actions are independent of the UI throttle. A status-only next frame
        // cannot erase this absolute target while it waits for mainHandler.
        val action = ScanCameraAction.from(advice)
        if (actionBuffer.offer(actionGenerationAtStart, action)) scheduleAction(binding)

        latestAdvice.set(advice)
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdvicePostMs < ADVICE_UI_INTERVAL_MS) return
        if (!adviceQueued.compareAndSet(false, true)) return
        val adviceEpoch = epoch.get()
        lastAdvicePostMs = now
        mainHandler.post { deliverAdvice(binding, adviceEpoch) }
    }

    private fun deliverAdvice(binding: ScanPreviewBinding, adviceEpoch: Int) {
        adviceQueued.set(false)
        if (!previewSessions.isCurrent(binding) || adviceEpoch != epoch.get()) return
        val advice = latestAdvice.getAndSet(null) ?: return
        onAdvice(advice)
    }

    private fun scheduleAction(binding: ScanPreviewBinding) {
        if (!previewSessions.isCurrent(binding)) return
        if (!actionQueued.compareAndSet(false, true)) return
        val actionEpoch = epoch.get()
        val requestId = actionRequest.incrementAndGet()
        mainHandler.post { deliverAction(binding, actionEpoch, requestId) }
    }

    private fun deliverAction(binding: ScanPreviewBinding, actionEpoch: Int, requestId: Int) {
        if (requestId != actionRequest.get()) return
        if (!previewSessions.isCurrent(binding) || actionEpoch != epoch.get()) {
            actionQueued.set(false)
            return
        }

        val action = actionBuffer.take()
        actionQueued.set(false)
        if (action != null) {
            target = target.copy(
                exposureEv = action.exposureEv ?: target.exposureEv,
                zoomRatio = action.zoomRatio ?: target.zoomRatio,
                focusMode = action.focusMode ?: target.focusMode,
            )
            enqueueApply()
        }
        // An action can arrive between take() and actionQueued=false; keep it moving.
        if (actionBuffer.hasPending()) scheduleAction(binding)
    }

    private fun markUserChange() {
        val nextEpoch = epoch.incrementAndGet()
        userEpoch.set(nextEpoch)
        latestAdvice.set(null)
        invalidateActions()
        enqueueApply()
    }

    private fun invalidateActions() {
        actionRequest.incrementAndGet()
        actionQueued.set(false)
        actionBuffer.invalidate()
    }

    private fun invalidatePreviewState() {
        capabilities = null
        epoch.incrementAndGet()
        userEpoch.set(-1)
        mainHandler.removeCallbacksAndMessages(null)
        applyRequest.incrementAndGet()
        applyQueued.set(false)
        adviceQueued.set(false)
        latestAdvice.set(null)
        lastAdvicePostMs = 0L
        invalidateActions()
    }

    private fun enqueueApply() {
        val binding = previewSessions.activeBinding ?: return
        if (!applyQueued.compareAndSet(false, true)) return
        val tokenEpoch = epoch.get()
        val requestId = applyRequest.incrementAndGet()
        mainHandler.post {
            if (requestId != applyRequest.get()) return@post
            if (!previewSessions.isCurrent(binding) || tokenEpoch != epoch.get()) {
                applyQueued.set(false)
                if (previewSessions.isCurrent(binding)) enqueueApply()
                return@post
            }
            val preview = previewProvider()
            if (!preview.isPreviewActive) {
                applyQueued.set(false)
                return@post
            }

            val snapshot = target
            preview.changeCameraParameters { parameters ->
                if (requestId != applyRequest.get() ||
                    !previewSessions.isCurrent(binding) ||
                    tokenEpoch != epoch.get()
                ) {
                    return@changeCameraParameters parameters
                }
                val caps = scanCameraCapabilities(parameters)
                capabilities = caps
                applyScanCameraTarget(parameters, snapshot)
                parameters
            }
            preview.changeCameraParameters { parameters ->
                val caps = scanCameraCapabilities(parameters)
                val actualExposureIndex = parameters.exposureCompensation
                val actualFocusMode = parameters.focusMode
                mainHandler.post {
                    if (requestId != applyRequest.get()) return@post
                    applyQueued.set(false)
                    if (!previewSessions.isCurrent(binding)) return@post
                    if (tokenEpoch != epoch.get()) {
                        enqueueApply()
                        return@post
                    }

                    // Only the first readback after an explicit user/mode change updates
                    // the exposure hint; auto readbacks must not keep rewriting it.
                    val userInitiated = userEpoch.compareAndSet(tokenEpoch, -1)
                    onCapabilities(caps, userInitiated)
                    onReadback(
                        ScanCameraReadback(
                            capabilities = caps,
                            requestedExposureEv = snapshot.exposureEv,
                            actualExposureIndex = actualExposureIndex,
                            actualFocusMode = actualFocusMode,
                            userInitiated = userInitiated,
                        ),
                    )
                    if (target != snapshot) enqueueApply()
                }
                parameters
            }
        }
    }

    private companion object {
        const val ADVICE_UI_INTERVAL_MS = 250L
    }
}
