package dev.rain.laundry.scanner

import dev.rain.laundry.core.ScanActionBuffer
import dev.rain.laundry.core.ScanAutoAdvice
import dev.rain.laundry.core.ScanAutoStatus
import dev.rain.laundry.core.ScanCameraAction
import dev.rain.laundry.core.ScanPreviewSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanActionDeliveryTest {
    @Test fun actionSurvivesFollowingStatusOnlyAdviceAndDelayedMessage() {
        val buffer = ScanActionBuffer()
        var delayed: (() -> Unit)? = null
        var queued = false
        val delivered = mutableListOf<ScanCameraAction>()

        fun onAdvice(advice: ScanAutoAdvice) {
            if (!buffer.offer(ScanCameraAction.from(advice))) return
            if (queued) return
            queued = true
            delayed = {
                queued = false
                buffer.take()?.let { delivered += it }
            }
        }

        onAdvice(ScanAutoAdvice(exposureEv = -0.5f, status = ScanAutoStatus.ADJUSTING))
        onAdvice(ScanAutoAdvice(status = ScanAutoStatus.STABLE))

        assertTrue("the status-only frame must not erase the pending action", buffer.hasPending())
        assertTrue("delivery is still delayed", delivered.isEmpty())
        assertNotNull(delayed)
        delayed!!.invoke()

        assertEquals(1, delivered.size)
        assertEquals(-0.5f, delivered.single().exposureEv!!, 0.001f)
        assertFalse(buffer.hasPending())
    }

    @Test fun mergesExposureAndZoomWithoutLosingEitherAbsoluteTarget() {
        val buffer = ScanActionBuffer()
        assertTrue(buffer.offer(ScanCameraAction(exposureEv = -1.0f)))
        assertTrue(buffer.offer(ScanCameraAction(zoomRatio = 1.5f)))
        val merged = buffer.take()
        assertNotNull(merged)
        assertEquals(-1.0f, merged!!.exposureEv!!, 0.001f)
        assertEquals(1.5f, merged.zoomRatio!!, 0.001f)
    }

    @Test fun invalidationDropsOldActionBeforeDelayedDelivery() {
        val buffer = ScanActionBuffer()
        assertTrue(buffer.offer(ScanCameraAction(exposureEv = -2.0f)))
        val oldGeneration = buffer.generation()
        var delivered: ScanCameraAction? = null
        val delayed = { delivered = buffer.take() }
        buffer.invalidate()
        assertFalse(buffer.hasPending())
        delayed()
        assertNull("a delayed message from before a manual/mode change must not apply", delivered)
        assertFalse("an in-flight decision from the previous generation is rejected",
            buffer.offer(oldGeneration, ScanCameraAction(exposureEv = 1.0f)))
    }

    @Test fun eachFactoryBindingStopsThePreviousPreviewSession() {
        val registry = ScanPreviewSessionRegistry()
        val first = registry.newBinding()
        assertTrue(registry.isCurrent(first))
        assertTrue(first.isActive)

        registry.stopCurrent()
        assertFalse(registry.isCurrent(first))
        assertFalse(first.isActive)

        val second = registry.newBinding()
        assertTrue(registry.isCurrent(second))
        assertTrue(second.isActive)
        assertFalse(registry.isCurrent(first))

        // The library fires previewStarted after createDecoder; the binding stays valid.
        assertTrue(registry.isCurrent(second))
        assertTrue(second.isActive)
    }
}
