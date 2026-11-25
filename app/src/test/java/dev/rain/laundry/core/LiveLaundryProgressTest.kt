package dev.rain.laundry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLaundryProgressTest {
    private val now = 1_000_000_000L

    private fun input(
        expectedEndAtMs: Long? = now + 90_000L,
        currentOwner: String = "owner-a",
        orderOwner: String = "owner-a",
        currentOrderKey: String? = "order-1",
        trackedOrderKey: String? = "order-1",
        orderKey: String = "order-1",
        isWater: Boolean = false,
        orderPhase: OrderPhase = OrderPhase.PAID,
        runState: LaundryRunState = LaundryRunState.RUNNING,
        completionConfirmed: Boolean = false,
        snapshotUpdatedAtMs: Long? = now,
        monitoringEnabled: Boolean = true,
        notificationsEnabled: Boolean = true,
        networkAvailable: Boolean = true,
        lastCheckFailed: Boolean = false,
        paused: Boolean = false,
        monitorStartedAtMs: Long? = null,
        maxMonitorDurationMs: Long = LiveLaundryProgressPolicy.DEFAULT_MAX_MONITOR_DURATION_MS,
        promotionSupported: Boolean = false,
        promotionPermission: Boolean = false,
        staleAfterMs: Long = LiveLaundryProgressPolicy.DEFAULT_STALE_AFTER_MS
    ) = LiveLaundryInput(
        nowMs = now, currentOwner = currentOwner, orderOwner = orderOwner,
        currentOrderKey = currentOrderKey, trackedOrderKey = trackedOrderKey, orderKey = orderKey,
        isWater = isWater, orderPhase = orderPhase, runState = runState,
        completionConfirmed = completionConfirmed, expectedEndAtMs = expectedEndAtMs,
        snapshotUpdatedAtMs = snapshotUpdatedAtMs, monitoringEnabled = monitoringEnabled,
        notificationsEnabled = notificationsEnabled, networkAvailable = networkAvailable,
        lastCheckFailed = lastCheckFailed, paused = paused, monitorStartedAtMs = monitorStartedAtMs,
        maxMonitorDurationMs = maxMonitorDurationMs, promotionSupported = promotionSupported,
        promotionPermission = promotionPermission, staleAfterMs = staleAfterMs
    )

    @Test
    fun runningWithFutureEstimateUsesSystemCountdownAndEstimateLabel() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = now + 90_000L,
            promotionSupported = true, promotionPermission = true))
        assertEquals(LiveLaundryPhase.RUNNING, decision.phase)
        assertTrue(decision.visible)
        assertEquals("order-1", decision.orderKey)
        assertEquals(now + 90_000L, decision.countdownEndAtMs)
        assertEquals(2L, decision.remainingMinutes)
        assertTrue(decision.isEstimate)
        assertTrue(decision.requestPromotion)
        assertFalse(decision.shouldStopService)
        assertEquals(LiveLaundryStopReason.NONE, decision.stopReason)
    }

    @Test
    fun positiveRemainderNeverDisplaysZeroMinutes() {
        assertEquals(1L, LiveLaundryProgressPolicy.minutesRemaining(1L))
        assertEquals(1L, LiveLaundryProgressPolicy.minutesRemaining(60_000L))
        assertEquals(2L, LiveLaundryProgressPolicy.minutesRemaining(60_001L))
        assertEquals(0L, LiveLaundryProgressPolicy.minutesRemaining(0L))
        assertEquals(0L, LiveLaundryProgressPolicy.minutesRemaining(-1L))
    }

    @Test
    fun unknownEstimateNeverFabricatesTime() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = null,
            promotionSupported = true, promotionPermission = true))
        assertEquals(LiveLaundryPhase.TIME_UNKNOWN, decision.phase)
        assertTrue(decision.visible)
        assertNull(decision.countdownEndAtMs)
        assertNull(decision.remainingMinutes)
        assertFalse(decision.isEstimate)
        assertFalse(decision.requestPromotion)
        assertEquals(LiveLaundryShortText.TIME_UNKNOWN, decision.shortText)
        assertFalse(decision.shouldStopService)
    }

    @Test
    fun zeroOrNegativeEstimateIsUnknownNotExpired() {
        val zero = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = 0L))
        assertEquals(LiveLaundryPhase.TIME_UNKNOWN, zero.phase)
        assertNull(zero.countdownEndAtMs)
        val negative = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = -1L))
        assertEquals(LiveLaundryPhase.TIME_UNKNOWN, negative.phase)
    }

    @Test
    fun expiredEstimateIsNotCompletion() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = now,
            promotionSupported = true, promotionPermission = true))
        assertEquals(LiveLaundryPhase.ESTIMATE_EXPIRED, decision.phase)
        assertTrue(decision.visible)
        assertNull(decision.countdownEndAtMs)
        assertEquals(0L, decision.remainingMinutes)
        assertFalse(decision.isEstimate)
        assertFalse(decision.requestPromotion)
        assertEquals(LiveLaundryShortText.WAITING_CONFIRMATION, decision.shortText)
        assertFalse(decision.shouldStopService)
        assertTrue(decision.stopReason != LiveLaundryStopReason.COMPLETED)
    }

    @Test
    fun longExpiredEstimateStillWaitsForPlatform() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = now - 86_400_000L))
        assertEquals(LiveLaundryPhase.ESTIMATE_EXPIRED, decision.phase)
        assertTrue(decision.visible)
        assertFalse(decision.phase == LiveLaundryPhase.FINISHED)
    }

    @Test
    fun offlineKeepsLastEstimateButLabelsOffline() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = now + 120_000L,
            networkAvailable = false, promotionSupported = true, promotionPermission = true))
        assertEquals(LiveLaundryPhase.OFFLINE, decision.phase)
        assertTrue(decision.visible)
        assertTrue(decision.offline)
        assertEquals(now + 120_000L, decision.countdownEndAtMs)
        assertEquals(2L, decision.remainingMinutes)
        assertFalse(decision.requestPromotion)
        assertFalse(decision.shouldStopService)
    }

    @Test
    fun failedCheckWithoutNetworkFlagIsStillOffline() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(lastCheckFailed = true))
        assertEquals(LiveLaundryPhase.OFFLINE, decision.phase)
        assertTrue(decision.offline)
    }

    @Test
    fun offlineWithExpiredEstimateNeverClaimsCompletion() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = now - 1L,
            networkAvailable = false))
        assertEquals(LiveLaundryPhase.ESTIMATE_EXPIRED, decision.phase)
        assertTrue(decision.visible)
        assertTrue(decision.offline)
        assertNull(decision.countdownEndAtMs)
        assertFalse(decision.phase == LiveLaundryPhase.FINISHED)
    }

    @Test
    fun unknownTimeOfflineStillHasAccurateOfflineState() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = null,
            networkAvailable = false))
        assertEquals(LiveLaundryPhase.OFFLINE, decision.phase)
        assertTrue(decision.offline)
        assertNull(decision.countdownEndAtMs)
        assertNull(decision.remainingMinutes)
        assertEquals(LiveLaundryShortText.TIME_UNKNOWN, decision.shortText)
    }

    @Test
    fun onlyServerConfirmedCompletionEndsTheLiveNotification() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(
            completionConfirmed = true, runState = LaundryRunState.FINISHED,
            orderPhase = OrderPhase.COMPLETED))
        assertEquals(LiveLaundryPhase.FINISHED, decision.phase)
        assertFalse(decision.visible)
        assertTrue(decision.shouldStopService)
        assertEquals(LiveLaundryStopReason.COMPLETED, decision.stopReason)
    }

    @Test
    fun completionFlagAloneWinsEvenWithInconsistentPhase() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(
            completionConfirmed = true, orderPhase = OrderPhase.PAID,
            runState = LaundryRunState.RUNNING))
        assertEquals(LiveLaundryPhase.FINISHED, decision.phase)
        assertFalse(decision.visible)
        assertEquals(LiveLaundryStopReason.COMPLETED, decision.stopReason)
    }

    @Test
    fun terminalOrderStopsWithoutBeingCalledFinished() {
        for (phase in listOf(OrderPhase.CANCELLED, OrderPhase.REJECTED, OrderPhase.CLOSED,
            OrderPhase.REFUNDING, OrderPhase.REFUNDED, OrderPhase.COMPLETED)) {
            val decision = LiveLaundryProgressPolicy.evaluate(input(orderPhase = phase))
            assertFalse(phase.name, decision.visible)
            assertTrue(phase.name, decision.shouldStopService)
            if (phase == OrderPhase.COMPLETED) {
                assertEquals(phase.name, LiveLaundryStopReason.ORDER_TERMINAL, decision.stopReason)
            }
        }
    }

    @Test
    fun waterOrderNeverGetsLiveProgress() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(isWater = true))
        assertFalse(decision.visible)
        assertEquals(LiveLaundryStopReason.WATER_ORDER, decision.stopReason)
    }

    @Test
    fun ownerMismatchStopsBeforeReadingState() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(currentOwner = "owner-b",
            promotionSupported = true, promotionPermission = true))
        assertFalse(decision.visible)
        assertTrue(decision.shouldStopService)
        assertEquals(LiveLaundryStopReason.OWNER_CHANGED, decision.stopReason)
    }

    @Test
    fun blankCurrentOwnerIsTreatedAsSignedOut() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(currentOwner = "  "))
        assertEquals(LiveLaundryStopReason.OWNER_CHANGED, decision.stopReason)
        assertFalse(decision.visible)
    }

    @Test
    fun ownerMismatchTakesPrecedenceOverCompletionAndOrderState() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(currentOwner = "owner-b",
            completionConfirmed = true, runState = LaundryRunState.FINISHED,
            orderPhase = OrderPhase.COMPLETED))
        assertEquals(LiveLaundryStopReason.OWNER_CHANGED, decision.stopReason)
        assertFalse(decision.visible)
    }

    @Test
    fun staleOrMissingCurrentOrderKeyStops() {
        val nullTop = LiveLaundryProgressPolicy.evaluate(input(currentOrderKey = null))
        assertEquals(LiveLaundryStopReason.ORDER_CHANGED, nullTop.stopReason)
        assertFalse(nullTop.visible)

        val blankTop = LiveLaundryProgressPolicy.evaluate(input(currentOrderKey = " "))
        assertEquals(LiveLaundryStopReason.ORDER_CHANGED, blankTop.stopReason)

        val otherTop = LiveLaundryProgressPolicy.evaluate(input(currentOrderKey = "order-2"))
        assertEquals(LiveLaundryStopReason.ORDER_CHANGED, otherTop.stopReason)
        assertFalse(otherTop.visible)

        val otherTracked = LiveLaundryProgressPolicy.evaluate(input(trackedOrderKey = "order-2"))
        assertEquals(LiveLaundryStopReason.ORDER_CHANGED, otherTracked.stopReason)

        val otherSnapshot = LiveLaundryProgressPolicy.evaluate(input(orderKey = "order-2"))
        assertEquals(LiveLaundryStopReason.ORDER_CHANGED, otherSnapshot.stopReason)
    }

    @Test
    fun orderMismatchWinsOverWaterFlag() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(currentOrderKey = "order-2",
            isWater = true))
        assertEquals(LiveLaundryStopReason.ORDER_CHANGED, decision.stopReason)
        assertFalse(decision.visible)
    }

    @Test
    fun monitoringAndNotificationPreferencesStopImmediately() {
        val disabled = LiveLaundryProgressPolicy.evaluate(input(monitoringEnabled = false))
        assertEquals(LiveLaundryStopReason.MONITORING_DISABLED, disabled.stopReason)
        assertFalse(disabled.visible)

        val notificationsOff = LiveLaundryProgressPolicy.evaluate(input(notificationsEnabled = false))
        assertEquals(LiveLaundryStopReason.NOTIFICATIONS_DISABLED, notificationsOff.stopReason)
        assertFalse(notificationsOff.visible)
    }

    @Test
    fun pausedMonitorHasNoLiveNotification() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(paused = true))
        assertEquals(LiveLaundryStopReason.PAUSED, decision.stopReason)
        assertFalse(decision.visible)
        assertTrue(decision.shouldStopService)
    }

    @Test
    fun nonRunningOrNotPaidOrdersStop() {
        val unknown = LiveLaundryProgressPolicy.evaluate(input(runState = LaundryRunState.UNKNOWN))
        assertEquals(LiveLaundryStopReason.NOT_RUNNING, unknown.stopReason)

        val created = LiveLaundryProgressPolicy.evaluate(input(orderPhase = OrderPhase.CREATED))
        assertEquals(LiveLaundryStopReason.NOT_RUNNING, created.stopReason)
    }

    @Test
    fun maximumDurationBoundaryStops() {
        val atLimit = LiveLaundryProgressPolicy.evaluate(input(monitorStartedAtMs = now - 12 * 60 * 60 * 1_000L))
        assertEquals(LiveLaundryStopReason.MAX_DURATION, atLimit.stopReason)
        assertFalse(atLimit.visible)

        val justBefore = LiveLaundryProgressPolicy.evaluate(input(
            monitorStartedAtMs = now - 12 * 60 * 60 * 1_000L + 1L))
        assertTrue(justBefore.visible)
        assertFalse(justBefore.shouldStopService)
    }

    @Test
    fun staleSnapshotIsLabeledAndNotPromoted() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(
            snapshotUpdatedAtMs = now - 5 * 60 * 1_000L - 1L,
            promotionSupported = true, promotionPermission = true))
        assertEquals(LiveLaundryPhase.RUNNING, decision.phase)
        assertTrue(decision.visible)
        assertTrue(decision.stale)
        assertEquals(now + 90_000L, decision.countdownEndAtMs)
        assertFalse(decision.requestPromotion)
    }

    @Test
    fun staleBoundaryExactlyFiveMinutesIsStillFresh() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(
            snapshotUpdatedAtMs = now - 5 * 60 * 1_000L,
            promotionSupported = true, promotionPermission = true))
        assertFalse(decision.stale)
        assertTrue(decision.requestPromotion)
    }

    @Test
    fun futureSnapshotCannotPromoteAnUnverifiedCountdown() {
        val decision = LiveLaundryProgressPolicy.evaluate(input(snapshotUpdatedAtMs = now + 1_000L,
            promotionSupported = true, promotionPermission = true))
        assertTrue(decision.stale)
        assertFalse(decision.requestPromotion)
    }

    @Test
    fun missingOrInvalidSnapshotTimeCannotPromote() {
        for (timestamp in listOf(null, 0L, -1L)) {
            val decision = LiveLaundryProgressPolicy.evaluate(input(snapshotUpdatedAtMs = timestamp,
                promotionSupported = true, promotionPermission = true))
            assertTrue(decision.stale)
            assertFalse(decision.requestPromotion)
            assertTrue(decision.visible)
        }
    }

    @Test
    fun omittedIdentityFailsClosed() {
        val decision = LiveLaundryProgressPolicy.evaluate(LiveLaundryInput(nowMs = now))
        assertFalse(decision.visible)
        assertTrue(decision.shouldStopService)
        assertEquals(LiveLaundryStopReason.OWNER_CHANGED, decision.stopReason)
    }

    @Test
    fun promotionNeedsBothPlatformSupportAndPermission() {
        val noSupport = LiveLaundryProgressPolicy.evaluate(input(promotionSupported = false,
            promotionPermission = true))
        assertFalse(noSupport.requestPromotion)

        val noPermission = LiveLaundryProgressPolicy.evaluate(input(promotionSupported = true,
            promotionPermission = false))
        assertFalse(noPermission.requestPromotion)
    }

    @Test
    fun expiredAndUnknownStatesAreNotPromotedEvenWhenSupported() {
        val expired = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = now - 1L,
            promotionSupported = true, promotionPermission = true))
        assertFalse(expired.requestPromotion)
        assertEquals(LiveLaundryShortText.WAITING_CONFIRMATION, expired.shortText)

        val unknown = LiveLaundryProgressPolicy.evaluate(input(expectedEndAtMs = null,
            promotionSupported = true, promotionPermission = true))
        assertFalse(unknown.requestPromotion)
    }
}
