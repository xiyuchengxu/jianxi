package dev.rain.laundry.core

/**
 * Pure, Android-free policy for the optional live laundry notification.
 *
 * The platform remains authoritative. A local estimate that reaches zero is *not* a
 * completion signal; only a server-confirmed [LaundryRunState.FINISHED] or
 * [RemoteOrderStatus.COMPLETED] can end the wash. This policy also owns the account/order
 * isolation rules so a stale worker, a switched account or a history row can never make the
 * monitor display another owner's wash.
 */
enum class LiveLaundryPhase {
    /** Server-confirmed running ordinary laundry with a future estimate. */
    RUNNING,
    /** Server-confirmed running laundry, but the platform supplied no usable end time. */
    TIME_UNKNOWN,
    /** The platform estimate has passed while the platform still reports running. */
    ESTIMATE_EXPIRED,
    /** The last read failed or the network is unavailable; the last server snapshot is still running. */
    OFFLINE,
    /** Only server-confirmed completion. The existing completion notification handles this. */
    FINISHED,
    /** No live notification; the service must stop. */
    STOPPED
}

enum class LiveLaundryStopReason {
    NONE,
    MONITORING_DISABLED,
    NOTIFICATIONS_DISABLED,
    OWNER_CHANGED,
    ORDER_CHANGED,
    WATER_ORDER,
    NOT_RUNNING,
    COMPLETED,
    ORDER_TERMINAL,
    PAUSED,
    MAX_DURATION
}

/** Short text for an OEM status chip. Countdown states intentionally leave this null so the
 * system can render its own chronometer/`when` countdown instead of a stale one-minute label. */
enum class LiveLaundryShortText {
    NONE,
    WAITING_CONFIRMATION,
    TIME_UNKNOWN
}

data class LiveLaundryDecision(
    val phase: LiveLaundryPhase,
    val orderKey: String? = null,
    val visible: Boolean = false,
    val expectedEndAtMs: Long? = null,
    val countdownEndAtMs: Long? = null,
    val remainingMinutes: Long? = null,
    val isEstimate: Boolean = false,
    val offline: Boolean = false,
    val stale: Boolean = false,
    val requestPromotion: Boolean = false,
    val shortText: LiveLaundryShortText = LiveLaundryShortText.NONE,
    val shouldStopService: Boolean = true,
    val stopReason: LiveLaundryStopReason = LiveLaundryStopReason.NONE
)

data class LiveLaundryInput(
    val nowMs: Long,
    val currentOwner: String = "",
    val orderOwner: String = "",
    val currentOrderKey: String? = null,
    val trackedOrderKey: String? = null,
    val orderKey: String = "",
    val isWater: Boolean = false,
    val isLaundry: Boolean = !isWater,
    val orderPhase: OrderPhase = OrderPhase.PAID,
    val runState: LaundryRunState = LaundryRunState.UNKNOWN,
    val completionConfirmed: Boolean = false,
    val expectedEndAtMs: Long? = null,
    val snapshotUpdatedAtMs: Long? = null,
    val monitoringEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val networkAvailable: Boolean = true,
    val lastCheckFailed: Boolean = false,
    val paused: Boolean = false,
    val monitorStartedAtMs: Long? = null,
    val maxMonitorDurationMs: Long = LiveLaundryProgressPolicy.DEFAULT_MAX_MONITOR_DURATION_MS,
    val promotionSupported: Boolean = false,
    val promotionPermission: Boolean = false,
    val staleAfterMs: Long = LiveLaundryProgressPolicy.DEFAULT_STALE_AFTER_MS
)

object LiveLaundryProgressPolicy {
    const val DEFAULT_MAX_MONITOR_DURATION_MS: Long = 12L * 60L * 60L * 1_000L
    const val DEFAULT_STALE_AFTER_MS: Long = 5L * 60L * 1_000L

    private val terminalPhases = setOf(
        OrderPhase.COMPLETED, OrderPhase.CANCELLED, OrderPhase.REJECTED,
        OrderPhase.CLOSED, OrderPhase.REFUNDING, OrderPhase.REFUNDED
    )

    fun evaluate(input: LiveLaundryInput): LiveLaundryDecision {
        if (!input.monitoringEnabled) return stop(LiveLaundryStopReason.MONITORING_DISABLED)
        if (!input.notificationsEnabled) return stop(LiveLaundryStopReason.NOTIFICATIONS_DISABLED)
        if (input.paused) return stop(LiveLaundryStopReason.PAUSED)

        // Identity is checked before order state so a stale snapshot cannot leak a device,
        // countdown or owner label after logout/switching accounts.
        val owner = input.currentOwner.trim()
        if (owner.isBlank() || input.orderOwner != owner) {
            return stop(LiveLaundryStopReason.OWNER_CHANGED)
        }
        val currentTop = input.currentOrderKey?.takeIf { it.isNotBlank() }
            ?: return stop(LiveLaundryStopReason.ORDER_CHANGED)
        val tracked = input.trackedOrderKey?.takeIf { it.isNotBlank() }
            ?: return stop(LiveLaundryStopReason.ORDER_CHANGED)
        val snapshotKey = input.orderKey.takeIf { it.isNotBlank() }
            ?: return stop(LiveLaundryStopReason.ORDER_CHANGED)
        if (currentTop != tracked || snapshotKey != tracked) {
            return stop(LiveLaundryStopReason.ORDER_CHANGED)
        }

        if (!input.isLaundry) return stop(LiveLaundryStopReason.WATER_ORDER)

        if (input.completionConfirmed || input.runState == LaundryRunState.FINISHED) {
            return stop(LiveLaundryStopReason.COMPLETED, LiveLaundryPhase.FINISHED)
        }
        if (input.orderPhase in terminalPhases) {
            return stop(LiveLaundryStopReason.ORDER_TERMINAL)
        }
        if (input.orderPhase != OrderPhase.PAID || input.runState != LaundryRunState.RUNNING) {
            return stop(LiveLaundryStopReason.NOT_RUNNING)
        }

        val startedAt = input.monitorStartedAtMs
        if (startedAt != null && input.nowMs >= startedAt &&
            input.nowMs - startedAt >= input.maxMonitorDurationMs.coerceAtLeast(0L)) {
            return stop(LiveLaundryStopReason.MAX_DURATION)
        }

        val expected = input.expectedEndAtMs?.takeIf { it > 0L }
        val stale = input.snapshotUpdatedAtMs?.let { updated ->
            updated <= 0L || updated > input.nowMs ||
                input.nowMs - updated > input.staleAfterMs.coerceAtLeast(0L)
        } ?: true
        val offline = !input.networkAvailable || input.lastCheckFailed

        if (expected == null) {
            return LiveLaundryDecision(
                phase = if (offline) LiveLaundryPhase.OFFLINE else LiveLaundryPhase.TIME_UNKNOWN,
                orderKey = tracked,
                visible = true,
                expectedEndAtMs = null,
                countdownEndAtMs = null,
                remainingMinutes = null,
                isEstimate = false,
                offline = offline,
                stale = stale,
                requestPromotion = false,
                shortText = LiveLaundryShortText.TIME_UNKNOWN,
                shouldStopService = false
            )
        }

        if (expected <= input.nowMs) {
            // Never turn an expired local estimate into a finished wash. The platform may still
            // be running the machine, or the completion callback may be delayed.
            return LiveLaundryDecision(
                phase = LiveLaundryPhase.ESTIMATE_EXPIRED,
                orderKey = tracked,
                visible = true,
                expectedEndAtMs = expected,
                countdownEndAtMs = null,
                remainingMinutes = 0L,
                isEstimate = false,
                offline = offline,
                stale = stale,
                requestPromotion = false,
                shortText = LiveLaundryShortText.WAITING_CONFIRMATION,
                shouldStopService = false
            )
        }

        val promotion = input.promotionSupported && input.promotionPermission &&
            !offline && !stale
        return LiveLaundryDecision(
            phase = if (offline) LiveLaundryPhase.OFFLINE else LiveLaundryPhase.RUNNING,
            orderKey = tracked,
            visible = true,
            expectedEndAtMs = expected,
            countdownEndAtMs = expected,
            remainingMinutes = minutesRemaining(expected - input.nowMs),
            isEstimate = true,
            offline = offline,
            stale = stale,
            requestPromotion = promotion,
            shortText = LiveLaundryShortText.NONE,
            shouldStopService = false
        )
    }

    /** Whole minutes, rounded up so a positive remainder never displays as 0 minutes. */
    internal fun minutesRemaining(remainingMs: Long): Long {
        if (remainingMs <= 0L) return 0L
        return remainingMs / 60_000L + if (remainingMs % 60_000L == 0L) 0L else 1L
    }

    private fun stop(reason: LiveLaundryStopReason,
        phase: LiveLaundryPhase = LiveLaundryPhase.STOPPED
    ): LiveLaundryDecision = LiveLaundryDecision(
        phase = phase,
        visible = false,
        shouldStopService = true,
        stopReason = reason
    )
}
