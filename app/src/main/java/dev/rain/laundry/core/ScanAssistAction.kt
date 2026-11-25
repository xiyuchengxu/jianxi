package dev.rain.laundry.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Camera target carried by an auto advice. UI status and camera actions are separated:
 * a later status-only advice must never erase an exposure/zoom/focus action that has not
 * reached the camera thread yet.
 */
data class ScanCameraAction(
    val exposureEv: Float? = null,
    val zoomRatio: Float? = null,
    val focusMode: ScanFocusMode? = null,
) {
    val hasTarget: Boolean
        get() = exposureEv != null || zoomRatio != null || focusMode != null

    fun mergedWith(newer: ScanCameraAction): ScanCameraAction = ScanCameraAction(
        exposureEv = newer.exposureEv ?: exposureEv,
        zoomRatio = newer.zoomRatio ?: zoomRatio,
        focusMode = newer.focusMode ?: focusMode,
    )

    companion object {
        val EMPTY = ScanCameraAction()

        fun from(advice: ScanAutoAdvice): ScanCameraAction = ScanCameraAction(
            exposureEv = advice.exposureEv,
            zoomRatio = advice.zoomRatio,
            focusMode = advice.focusMode,
        )
    }
}

/**
 * Thread-safe pending action buffer. Status-only frames do not clear it; absolute values
 * from successive frames are merged field by field. Invalidating a preview/user mode
 * clears it before any delayed delivery can apply an old action.
 */
class ScanActionBuffer {
    private var pending: ScanCameraAction? = null
    private var generation = 0

    fun generation(): Int = synchronized(this) { generation }

    @Synchronized
    fun offer(action: ScanCameraAction): Boolean = offer(generation, action)

    @Synchronized
    fun offer(expectedGeneration: Int, action: ScanCameraAction): Boolean {
        if (expectedGeneration != generation) return false
        if (!action.hasTarget) return false
        pending = pending?.mergedWith(action) ?: action
        return true
    }

    @Synchronized
    fun take(): ScanCameraAction? {
        val result = pending
        pending = null
        return result
    }

    /** Invalidates every action created before this call, including a concurrent offer. */
    @Synchronized
    fun invalidate() {
        generation++
        pending = null
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null
}

/** A decoder-thread session created at factory.createDecoder() time. */
data class ScanPreviewBinding(
    val session: ScanAssistSession,
    val token: Int,
) {
    val isActive: Boolean get() = session.isActive(token)
}

/**
 * One binding per actual decoder creation. The library creates a decoder before it fires
 * CameraPreview.StateListener.previewStarted, so the session must be created here and
 * must not be replaced by the state callback. Stopping/restarting immediately invalidates
 * the previous decoder session; a new decoder gets a different token.
 */
class ScanPreviewSessionRegistry {
    private val current = AtomicReference<ScanPreviewBinding?>(null)

    fun newBinding(): ScanPreviewBinding {
        current.getAndSet(null)?.session?.stop()
        val session = ScanAssistSession()
        val binding = ScanPreviewBinding(session, session.start())
        current.set(binding)
        return binding
    }

    fun stopCurrent() {
        current.getAndSet(null)?.session?.stop()
    }

    fun isCurrent(binding: ScanPreviewBinding): Boolean =
        current.get() === binding && binding.isActive

    val activeBinding: ScanPreviewBinding?
        get() = current.get()?.takeIf { it.isActive }
}
