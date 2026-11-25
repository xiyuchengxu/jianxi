package dev.rain.laundry.cloud

/** The user-visible cloud reminder states. A provider acceptance is never "delivered". */
enum class CloudReminderStatus { DISABLED, BOUND, PENDING, ERROR, AUTH_REQUIRED }

enum class CloudTestStatus(val wire: String) {
    ACCEPTED("accepted"), UNKNOWN("unknown"), FAILED("failed");

    companion object {
        fun fromWire(value: String): CloudTestStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Local operation outcome used only to choose a translated, non-sensitive UI message. */
enum class CloudIssueKind {
    NETWORK, AUTH_REQUIRED, PAIRING_REJECTED, INVALID_URL, LOGIN_REQUIRED, CONSENT_REQUIRED,
    SERVER_REJECTED, RATE_LIMITED, INVALID_RESPONSE, UNBIND_FAILED, NOT_CONFIGURED,
    NOT_BOUND, NOTIFICATION_FAILED, SYNC_PENDING, SUBSCRIPTION_REJECTED,
    DEVICE_REPORT_REJECTED, VERSION_UNSUPPORTED, GENERIC
}

data class CloudIssue(val kind: CloudIssueKind, val detail: String? = null,
    /** Allowlisted upstream category; never a raw server message. */
    val reasonCode: String? = null)

data class CloudBinding(
    val owner: String,
    val serverUrl: String,
    val deviceToken: String,
    val accountId: String,
    val deviceId: String,
    val deviceLabel: String,
    val notificationConfigured: Boolean = false,
    val accountStatus: String = "active",
    val consentToReadOnlyQuery: Boolean = true,
    /** Primitive Boolean keeps old Gson records at false; enabling is an explicit opt-in. */
    val deviceAssisted: Boolean = false
)

/**
 * Mutable configuration is excluded; every remote credential and routing key must match.
 * The routing mode is part of the key so an in-flight platform-mode reply cannot commit after
 * the user enables device-assisted reporting (and vice versa).
 */
internal fun CloudBinding?.sameCredentialsAs(expected: CloudBinding?): Boolean =
    if (this == null || expected == null) this == null && expected == null
    else owner == expected.owner && serverUrl == expected.serverUrl &&
        accountId == expected.accountId && deviceId == expected.deviceId &&
        deviceToken == expected.deviceToken && deviceAssisted == expected.deviceAssisted

data class CloudPendingUnbind(
    val owner: String,
    val serverUrl: String,
    val deviceToken: String,
    val accountId: String,
    val deviceId: String,
    val createdAtMs: Long,
    val attempts: Int = 0,
    val lastError: String? = null
)

enum class CloudOrderSyncState { PENDING, SYNCED, REJECTED }

data class CloudOrderSyncRecord(
    val orderKey: String,
    val orderId: String,
    val orderNo: String,
    val state: CloudOrderSyncState,
    val attemptedAtMs: Long,
    val lastIssue: String? = null,
    /** Number of not-running 409 retries consumed for this order; old records default to zero. */
    val attempts: Int = 0,
    /** Allowlisted upstream reason from the server; old records default to null. */
    val reasonCode: String? = null
)

/**
 * Encrypted device-assisted tracking evidence. It deliberately stores the phone's actual
 * observation, not a fabricated server completion: running first, then an optional completed or
 * cancelled event. `runningAcked=false` is what forces an offline running→completed transition
 * to register running before completed.
 */
data class CloudDeviceReportRecord(
    val orderKey: String,
    val orderId: String,
    val orderNo: String,
    val runningObservedAtMs: Long,
    val runningExpectedEndAtMs: Long? = null,
    val runningAcked: Boolean = false,
    val completionObservedAtMs: Long? = null,
    val completionAcked: Boolean = false,
    val cancelledObservedAtMs: Long? = null,
    val cancelledAcked: Boolean = false,
    val lastReportedState: String? = null,
    val lastReportedObservedAtMs: Long? = null,
    val lastReportedExpectedEndAtMs: Long? = null,
    // Server readback (source/client_state/completion_source) is display-only.
    val serverState: String? = null,
    val serverSource: String? = null,
    val serverClientState: String? = null,
    val completionSource: String? = null,
    val estimatedDeliveryState: String? = null,
    val estimatedAccepted: Boolean = false,
    val estimatedDelivered: Boolean = false,
    val lastIssue: String? = null,
    val reasonCode: String? = null,
    /** A fixed 3xx/4xx/unsupported response stops automatic retries until explicit retry. */
    val blocked: Boolean = false,
    val unsupported: Boolean = false
)

/** Encrypted per-owner record. The pairing code and 喵码 are deliberately absent. */
data class CloudStoredState(
    val owner: String,
    val binding: CloudBinding? = null,
    val pendingUnbinds: List<CloudPendingUnbind> = emptyList(),
    val orders: Map<String, CloudOrderSyncRecord> = emptyMap(),
    /** Device-assisted records are separate from platform-mode order records. */
    val deviceReports: Map<String, CloudDeviceReportRecord> = emptyMap()
)

/**
 * Gson/Unsafe cannot apply Kotlin defaults for a newly added field, so an old encrypted record
 * may contain null here at runtime. Normalize once on load; no rebind or migration is required.
 */
internal fun CloudStoredState.normalizedForLoad(): CloudStoredState {
    val reports = runCatching { deviceReports }.getOrNull()
    val orderMap = runCatching { orders }.getOrNull()
    val unbind = runCatching { pendingUnbinds }.getOrNull()
    return if (reports != null && orderMap != null && unbind != null) this
    else CloudStoredState(owner, binding, unbind ?: emptyList(),
        orderMap ?: emptyMap(), reports ?: emptyMap())
}

data class CloudOrderReport(
    val orderId: String,
    val orderNo: String,
    val state: String,
    val observedAtMs: Long,
    val expectedEndAtMs: Long? = null
)

data class CloudSubscriptionSummary(
    val id: String,
    val orderId: String? = null,
    val orderNo: String? = null,
    val state: String,
    val deliveryState: String,
    val accepted: Boolean,
    val delivered: Boolean,
    val lastError: String? = null,
    val source: String? = null,
    val expectedEndAtMs: Long? = null,
    /** Server echo of the accepted observation; required for a device-report ACK. */
    val observedAtMs: Long? = null,
    val clientState: String? = null,
    val completionSource: String? = null,
    val estimatedDeliveryState: String? = null,
    val estimatedAccepted: Boolean = false,
    val estimatedDelivered: Boolean = false
)

data class CloudServerStatus(
    val accountStatus: String,
    val notificationConfigured: Boolean,
    val paused: Boolean,
    val subscriptions: List<CloudSubscriptionSummary>
)

data class CloudBindResponse(
    val deviceToken: String,
    val accountId: String,
    val deviceId: String
)

data class CloudTestResult(val status: CloudTestStatus, val atMs: Long)

data class CloudReminderUiState(
    val status: CloudReminderStatus = CloudReminderStatus.DISABLED,
    val bound: Boolean = false,
    val serverUrl: String = CloudReminderEndpoint.DEFAULT_SERVER_URL,
    val accountId: String? = null,
    val deviceLabel: String? = null,
    val notificationConfigured: Boolean = false,
    val accountStatus: String = "",
    val subscriptions: List<CloudSubscriptionSummary> = emptyList(),
    /** Explicit phone-assisted mode; false for every old binding and platform-verification mode. */
    val deviceAssisted: Boolean = false,
    /** Latest encrypted device report evidence/readback for the current tracked order. */
    val deviceReport: CloudDeviceReportRecord? = null,
    val pendingSync: Boolean = false,
    val busy: Boolean = false,
    val issue: CloudIssue? = null,
    val testResult: CloudTestResult? = null,
    val cooldownUntilMs: Long = 0L,
    /** Incremented after binding or updating the one-time 喵码 so the UI clears secrets. */
    val clearSecretsToken: Long = 0L
)

/** One eligible running, paid, ordinary laundry order. Water and history are never eligible. */
data class CloudEligibleOrder(val orderKey: String, val orderId: String, val orderNo: String)

/**
 * How a failed subscription request may be retried.
 *
 * The subscription body contains only identifiers already held locally, so a 3xx/4xx response
 * normally cannot be repaired by sending the same request again. Only transport failures,
 * throttling and server-side 5xx responses are transient. 401/403 need the account reauthorization
 * flow. The one 409 race (payment acknowledged before the order reports IN_USE) is handled by
 * [CloudSyncFailurePolicy.mayRetryNotRunning] with a small bounded retry.
 */
enum class CloudFailureClass { TRANSIENT, AUTH_REQUIRED, TERMINAL }

/**
 * The only upstream categories the Android client may persist or display. This mirrors the
 * server's PLATFORM_ERROR_CODES allowlist; an unknown or malicious value is ignored and the
 * decision falls back to the HTTP status. Raw detail messages are never surfaced.
 */
object CloudUpstream {
    val ALLOWED: Set<String> = setOf(
        "upstream_unavailable", "upstream_response_too_large", "upstream_http_403",
        "upstream_http_405", "upstream_http_429", "upstream_http_5xx",
        "upstream_http_error", "upstream_invalid_response", "upstream_auth_required",
        "upstream_rejected", "identity_mismatch"
    )
    private val RETRYABLE = setOf(
        "upstream_unavailable", "upstream_http_429", "upstream_http_5xx"
    )
    private val AUTH_REQUIRED = setOf("upstream_auth_required")

    fun sanitize(raw: String?): String? = raw?.trim()?.takeIf { it in ALLOWED }

    fun isRetryable(code: String?): Boolean = code in RETRYABLE

    fun isAuthRequired(code: String?): Boolean = code in AUTH_REQUIRED

    /** Fixed display status for a known category; never parsed from a free-form message. */
    fun displayStatus(code: String?): String? = when (code) {
        "upstream_http_403" -> "403"
        "upstream_http_405" -> "405"
        "upstream_http_429" -> "429"
        "upstream_http_5xx" -> "5xx"
        else -> null
    }
}

object CloudSyncFailurePolicy {
    /**
     * [retryableHint] is the server's optional explicit classification; false always wins except
     * for an authentication requirement. [reasonCode] is already allowlisted by [CloudUpstream].
     * A missing hint/known reason falls back to the HTTP class so older servers keep working.
     */
    fun classifyHttp(statusCode: Int, retryableHint: Boolean? = null,
        reasonCode: String? = null
    ): CloudFailureClass = when {
        statusCode == 401 || statusCode == 403 -> CloudFailureClass.AUTH_REQUIRED
        CloudUpstream.isAuthRequired(reasonCode) -> CloudFailureClass.AUTH_REQUIRED
        retryableHint == false -> CloudFailureClass.TERMINAL
        reasonCode != null -> if (CloudUpstream.isRetryable(reasonCode))
            CloudFailureClass.TRANSIENT else CloudFailureClass.TERMINAL
        statusCode == 408 || statusCode == 425 || statusCode == 429 -> CloudFailureClass.TRANSIENT
        statusCode in 500..599 -> CloudFailureClass.TRANSIENT
        else -> CloudFailureClass.TERMINAL
    }

    /**
     * The 409 not-running race may be deferred only when the server did not explicitly say the
     * rejection is permanent. `retryable=false` is terminal immediately.
     */
    fun shouldDeferNotRunning(statusCode: Int, retryableHint: Boolean?,
        failedAttempts: Int
    ): Boolean = statusCode == 409 && retryableHint != false &&
        mayRetryNotRunning(failedAttempts)

    /** Sanitized HTTP status for UI/diagnostics; never a server message or an order identifier. */
    fun httpCode(issue: String?): String? = issue
        ?.takeIf { it.startsWith("http_") }
        ?.removePrefix("http_")
        ?.toIntOrNull()
        ?.takeIf { it in 100..599 }
        ?.toString()

    fun httpIssue(statusCode: Int): String = "http_$statusCode"

    /**
     * The server rejects both an order that has not reached IN_USE yet and one that has already
     * completed with the same 409. Absorb the short payment-to-running race with a small bounded
     * retry; after that the rejection becomes visible and requires an explicit manual retry.
     */
    fun mayRetryNotRunning(failedAttempts: Int): Boolean =
        failedAttempts < MAX_NOT_RUNNING_ATTEMPTS

    const val MAX_NOT_RUNNING_ATTEMPTS = 3
}

object CloudReminderStateRules {
    /** One deterministic mapping used by the UI so failure and auth states cannot be ambiguous. */
    fun status(bound: Boolean, accountStatus: String = "", pending: Boolean = false,
        issue: CloudIssue? = null
    ): CloudReminderStatus {
        if (!bound) return CloudReminderStatus.DISABLED
        if (accountStatus in setOf("auth_required", "unbound") ||
            issue?.kind == CloudIssueKind.AUTH_REQUIRED)
            return CloudReminderStatus.AUTH_REQUIRED
        // A terminal rejection must never look like a transient "waiting to sync" state.
        if (issue?.kind == CloudIssueKind.SUBSCRIPTION_REJECTED) return CloudReminderStatus.ERROR
        if (pending || issue?.kind == CloudIssueKind.SYNC_PENDING) return CloudReminderStatus.PENDING
        if (issue != null) return CloudReminderStatus.ERROR
        return CloudReminderStatus.BOUND
    }

    /** A rejected record remains visible until a new order or an explicit retry replaces it. */
    fun rejectedIssue(records: Collection<CloudOrderSyncRecord>): CloudIssue? =
        records.firstOrNull { it.state == CloudOrderSyncState.REJECTED }?.let {
            CloudIssue(CloudIssueKind.SUBSCRIPTION_REJECTED,
                CloudSyncFailurePolicy.httpCode(it.lastIssue),
                CloudUpstream.sanitize(it.reasonCode))
        }

    /** Preserve a sanitized HTTP status for a still-retryable failure across app restarts. */
    fun pendingIssue(records: Collection<CloudOrderSyncRecord>): CloudIssue? =
        records.firstOrNull { it.state == CloudOrderSyncState.PENDING }?.let { record ->
            val reason = CloudUpstream.sanitize(record.reasonCode)
            val status = CloudSyncFailurePolicy.httpCode(record.lastIssue)
            if (reason == null && status == null) null
            else CloudIssue(CloudIssueKind.SYNC_PENDING, status, reason)
        }
}

object CloudReminderPolicy {
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * Local evidence only. PAID is the payment acknowledgement; a confirmed FINISHED snapshot and
     * every non-laundry order are excluded. A missing snapshot is allowed because the app often
     * subscribes immediately after the payment callback, before the first detail refresh.
     */
    fun eligible(order: dev.rain.laundry.core.LocalOrder, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!order.isLaundry || order.owner.isBlank()) return false
        if (order.phase != dev.rain.laundry.core.OrderPhase.PAID) return false
        if (order.orderId.isBlank() && order.orderNo.isBlank()) return false
        if (order.laundryProgress?.runState == dev.rain.laundry.core.LaundryRunState.FINISHED) return false
        return nowMs - order.createdAt in 0..MAX_AGE_MS
    }

    fun eligibleOrder(order: dev.rain.laundry.core.LocalOrder, nowMs: Long = System.currentTimeMillis()):
        CloudEligibleOrder? {
        if (!eligible(order, nowMs)) return null
        return CloudEligibleOrder(dev.rain.laundry.core.OrderHistory.key(order), order.orderId, order.orderNo)
    }

    /**
     * The server contract accepts one running order at a time here. Prefer the current workflow
     * pointer, then the newest eligible record; never fan out historical PAID records.
     */
    fun selectSingle(orders: List<dev.rain.laundry.core.LocalOrder>,
        current: dev.rain.laundry.core.LocalOrder?, nowMs: Long = System.currentTimeMillis()
    ): CloudEligibleOrder? {
        // The workflow pointer is authoritative. If it is finished, water or otherwise ineligible,
        // do not resurrect an older history record as a new subscription.
        if (current != null) return eligibleOrder(current, nowMs)
        return orders.asSequence().filter { eligible(it, nowMs) }
            .sortedByDescending { it.createdAt }
            .mapNotNull { eligibleOrder(it, nowMs) }
            .firstOrNull()
    }
}
