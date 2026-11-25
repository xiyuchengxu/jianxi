package dev.rain.laundry.cloud

import android.content.Context
import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class CloudSyncOutcome {
    NO_BINDING, NO_ELIGIBLE_ORDER, ALREADY_SYNCED, SYNCED, PENDING, AUTH_REQUIRED, REJECTED,
    DEVICE_WAITING, UNSUPPORTED
}

data class CloudUnbindOutcome(val attempted: Int, val succeeded: Int, val pending: Int)

/**
 * Pure application operations shared by the foreground and WorkManager paths. No platform laundry
 * write endpoint is ever called here; the only order data is the identifier already held locally.
 */
class CloudReminderOperations(
    context: Context,
    private val api: CloudReminderApi = CloudReminderApi()
) {
    private val session = SessionVault(context.applicationContext)
    private val vault = CloudReminderVault(context.applicationContext)

    /** Persist local intent before either the immediate request or a durable worker can run. */
    fun queue(owner: String, expected: CloudBinding? = null): CloudSyncOutcome {
        val binding = modeBinding(owner, expected) ?: return CloudSyncOutcome.NO_BINDING
        return if (binding.deviceAssisted) queueDevice(owner, binding)
        else prepare(owner, expected, force = false).outcome
    }

    /**
     * @param force true only for an explicit user retry. Automatic callers must leave a terminal
     * server rejection alone instead of retrying a request that cannot succeed unchanged.
     */
    suspend fun sync(owner: String, expectedBinding: CloudBinding? = null,
        force: Boolean = false
    ): CloudSyncOutcome = ownerLock(owner).withLock {
        val binding = modeBinding(owner, expectedBinding)
            ?: return@withLock CloudSyncOutcome.NO_BINDING
        if (binding.deviceAssisted) syncDevice(owner, binding, force)
        else syncPlatform(owner, expectedBinding, force)
    }

    private suspend fun syncPlatform(owner: String, expectedBinding: CloudBinding?,
        force: Boolean
    ): CloudSyncOutcome {
        val preparation = prepare(owner, expectedBinding, force)
        val queued = preparation.order ?: return preparation.outcome
        val binding = queued.binding
        return try {
            when (api.subscribe(binding.serverUrl, binding.deviceToken, queued.candidate)) {
                is CloudSubscribeOutcome.Created, CloudSubscribeOutcome.AlreadySubscribed ->
                    commit(queued, CloudOrderSyncState.SYNCED, CloudSyncOutcome.SYNCED, null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (auth: CloudAuthRequired) {
            commit(queued, CloudOrderSyncState.PENDING, CloudSyncOutcome.AUTH_REQUIRED,
                "auth_required", queued.attempts, CloudUpstream.sanitize(auth.reasonCode))
        } catch (_: CloudRateLimited) {
            commit(queued, CloudOrderSyncState.PENDING, CloudSyncOutcome.PENDING,
                CloudSyncFailurePolicy.httpIssue(429), queued.attempts)
        } catch (_: CloudNetworkUnavailable) {
            commit(queued, CloudOrderSyncState.PENDING, CloudSyncOutcome.PENDING, "network",
                queued.attempts)
        } catch (rejected: CloudRejected) {
            // A 4xx rejection is not fixed by sending the same identifiers again. Keep the failure
            // as a visible, manual-retryable rejection instead of an endless "pending" state.
            // The one exception is the server's shared 409 for not-yet-running and completed
            // orders: a payment-to-IN_USE race is common, so allow a small bounded number of
            // automatic attempts before surfacing the terminal rejection.
            val failedAttempts = queued.attempts + 1
            val reasonCode = CloudUpstream.sanitize(rejected.reasonCode)
            val failureClass = CloudSyncFailurePolicy.classifyHttp(rejected.statusCode,
                rejected.retryableHint, reasonCode)
            when {
                CloudSyncFailurePolicy.shouldDeferNotRunning(rejected.statusCode,
                    rejected.retryableHint, failedAttempts) ->
                    commit(queued, CloudOrderSyncState.PENDING, CloudSyncOutcome.PENDING,
                        CloudSyncFailurePolicy.httpIssue(409), failedAttempts, reasonCode)
                failureClass == CloudFailureClass.TRANSIENT ->
                    commit(queued, CloudOrderSyncState.PENDING, CloudSyncOutcome.PENDING,
                        CloudSyncFailurePolicy.httpIssue(rejected.statusCode), queued.attempts,
                        reasonCode)
                failureClass == CloudFailureClass.AUTH_REQUIRED ->
                    commit(queued, CloudOrderSyncState.PENDING, CloudSyncOutcome.AUTH_REQUIRED,
                        "auth_required", failedAttempts, reasonCode)
                else -> commit(queued, CloudOrderSyncState.REJECTED, CloudSyncOutcome.REJECTED,
                    CloudSyncFailurePolicy.httpIssue(rejected.statusCode), failedAttempts,
                    reasonCode)
            }
        } catch (_: CloudReminderException) {
            // An unexpected response is surfaced and retried only by an explicit user action.
            commit(queued, CloudOrderSyncState.REJECTED, CloudSyncOutcome.REJECTED, "protocol",
                queued.attempts)
        }
    }

    private data class QueuedOrder(
        val token: String,
        val binding: CloudBinding,
        val candidate: CloudEligibleOrder,
        val attemptedAtMs: Long,
        val attempts: Int
    )

    private data class Preparation(val outcome: CloudSyncOutcome, val order: QueuedOrder? = null)

    private data class DevicePreparation(
        val token: String = "",
        val orderKey: String? = null,
        val report: CloudOrderReport? = null,
        val outcome: CloudSyncOutcome = CloudSyncOutcome.NO_BINDING
    )

    private fun modeBinding(owner: String, expected: CloudBinding?): CloudBinding? {
        if (owner.isBlank()) return null
        val token = session.token()
        var result: CloudBinding? = null
        session.withLaundrySession(owner, token, { true }) {
            result = vault.binding(owner)?.takeIf {
                it.owner == owner && it.consentToReadOnlyQuery && it.deviceToken.isNotBlank() &&
                    (expected == null || it.sameCredentialsAs(expected))
            }
            true
        }
        return result
    }

    /** A history order may update existing evidence, but can never start new tracking. */
    fun queueObservation(owner: String, order: dev.rain.laundry.core.LocalOrder,
        expected: CloudBinding? = null, fresh: Boolean = true
    ): CloudSyncOutcome {
        if (order.owner != owner || !order.isLaundry) return CloudSyncOutcome.NO_ELIGIBLE_ORDER
        val binding = modeBinding(owner, expected)?.takeIf { it.deviceAssisted }
            ?: return CloudSyncOutcome.NO_BINDING
        return prepareDevice(owner, binding, false, order.takeIf { fresh }).outcome
    }

    private fun queueDevice(owner: String, binding: CloudBinding): CloudSyncOutcome =
        prepareDevice(owner, binding, false).outcome

    private suspend fun syncDevice(owner: String, binding: CloudBinding,
        force: Boolean
    ): CloudSyncOutcome {
        var manual = force
        // A worker can flush several offline transitions without starving the newest order.
        // The hard batch limit also prevents a changing foreground snapshot spinning forever.
        repeat(64) {
            val prepared = prepareDevice(owner, binding, manual)
            val report = prepared.report ?: return prepared.outcome
            if (!deviceSessionCurrent(owner, binding, prepared.token)) return CloudSyncOutcome.NO_BINDING
            try {
                val receipt = api.reportOrder(binding.serverUrl, binding.deviceToken, report)
                if (!commitDeviceAck(owner, binding, prepared, receipt) &&
                    !deviceSessionCurrent(owner, binding, prepared.token)) return CloudSyncOutcome.NO_BINDING
                manual = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: CloudAuthRequired) {
                return commitDeviceFailure(owner, binding, prepared, CloudSyncOutcome.AUTH_REQUIRED) {
                    CloudDeviceReportPolicy.transientFailure(it, "auth_required",
                        CloudUpstream.sanitize(auth.reasonCode))
                }
            } catch (_: CloudRateLimited) {
                return commitDeviceFailure(owner, binding, prepared, CloudSyncOutcome.PENDING) {
                    CloudDeviceReportPolicy.transientFailure(it, CloudSyncFailurePolicy.httpIssue(429))
                }
            } catch (_: CloudNetworkUnavailable) {
                return commitDeviceFailure(owner, binding, prepared, CloudSyncOutcome.PENDING) {
                    CloudDeviceReportPolicy.transientFailure(it, "network")
                }
            } catch (_: CloudUnsupportedFeature) {
                return commitDeviceFailure(owner, binding, prepared, CloudSyncOutcome.UNSUPPORTED) {
                    CloudDeviceReportPolicy.unsupportedFailure(it)
                }
            } catch (rejected: CloudRejected) {
                val reason = CloudUpstream.sanitize(rejected.reasonCode)
                val classification = CloudSyncFailurePolicy.classifyHttp(rejected.statusCode,
                    rejected.retryableHint, reason)
                val outcome = when (classification) {
                    CloudFailureClass.TRANSIENT -> CloudSyncOutcome.PENDING
                    CloudFailureClass.AUTH_REQUIRED -> CloudSyncOutcome.AUTH_REQUIRED
                    CloudFailureClass.TERMINAL -> CloudSyncOutcome.REJECTED
                }
                return commitDeviceFailure(owner, binding, prepared, outcome) {
                    if (classification == CloudFailureClass.TRANSIENT)
                        CloudDeviceReportPolicy.transientFailure(it,
                            CloudSyncFailurePolicy.httpIssue(rejected.statusCode), reason)
                    else CloudDeviceReportPolicy.terminalFailure(it,
                        CloudSyncFailurePolicy.httpIssue(rejected.statusCode), reason)
                }
            } catch (_: CloudReminderException) {
                return commitDeviceFailure(owner, binding, prepared, CloudSyncOutcome.REJECTED) {
                    CloudDeviceReportPolicy.terminalFailure(it, "protocol")
                }
            }
        }
        return prepareDevice(owner, binding, false).outcome
    }

    private fun prepareDevice(owner: String, binding: CloudBinding, force: Boolean,
        observation: dev.rain.laundry.core.LocalOrder? = null
    ): DevicePreparation {
        if (owner.isBlank()) return DevicePreparation()
        val token = session.token()
        var prepared = DevicePreparation()
        session.withLaundrySession(owner, token, { true }) {
            val currentBinding = vault.binding(owner)?.takeIf {
                it.sameCredentialsAs(binding) && it.deviceAssisted
            } ?: return@withLaundrySession false
            val current = session.order()?.takeIf { it.owner == owner }
            val now = System.currentTimeMillis()
            fun observed(order: dev.rain.laundry.core.LocalOrder, fresh: Boolean = false): CloudDeviceObservation? {
                val observedAt = order.laundryProgress?.updatedAt ?: if (fresh) now else return null
                return CloudDeviceObservation(
                    ownerMatches = order.owner == owner, isWater = order.isWater, isLaundry = order.isLaundry, phase = order.phase,
                    runState = order.laundryProgress?.runState,
                    completionConfirmed = order.laundryProgress?.completionConfirmed == true,
                    expectedEndAtMs = order.laundryProgress?.expectedEndAt,
                    observedAtMs = observedAt,
                    orderKey = OrderHistory.key(order), orderId = order.orderId, orderNo = order.orderNo,
                    nowMs = now)
            }
            val stored = (session.laundryReminders().map { it.order }.filter { it.owner == owner } +
                session.orders().filter { it.owner == owner } + listOfNotNull(current))
                .associateBy(OrderHistory::key)
            // Explicit fresh engine observations only update a matching persisted order. The
            // current workflow alone is passed separately as the source of new tracking.
            val fresh = observation?.takeIf { candidate ->
                stored[OrderHistory.key(candidate)]?.let { persisted ->
                    OrderHistory.sameOrder(persisted, candidate) &&
                        persisted.phase == candidate.phase && persisted.laundryProgress == candidate.laundryProgress
                } == true
            }
            var records: Map<String, CloudDeviceReportRecord> = emptyMap()
            val saved = vault.updateDeviceReportsIfBound(owner, currentBinding) { previous ->
                records = CloudDeviceReportQueue.observe(previous, current?.let { observed(it) },
                    stored.values.mapNotNull { observed(it) } + listOfNotNull(fresh?.let { observed(it, true) }), force)
                records
            }
            if (!saved) return@withLaundrySession false
            val next = CloudDeviceReportQueue.next(records)
            val outcome = when {
                next != null -> CloudSyncOutcome.PENDING
                records.values.any { it.unsupported } -> CloudSyncOutcome.UNSUPPORTED
                records.values.any { it.blocked } -> CloudSyncOutcome.REJECTED
                records.isNotEmpty() -> CloudSyncOutcome.DEVICE_WAITING
                else -> CloudSyncOutcome.NO_ELIGIBLE_ORDER
            }
            prepared = DevicePreparation(token, next?.first, next?.second, outcome)
            true
        }
        return prepared
    }

    private fun deviceSessionCurrent(owner: String, binding: CloudBinding, token: String): Boolean =
        session.withLaundrySession(owner, token, { true }) {
            vault.binding(owner).sameCredentialsAs(binding)
        }

    private fun commitDeviceAck(owner: String, binding: CloudBinding, prepared: DevicePreparation,
        receipt: CloudSubscriptionSummary
    ): Boolean {
        val key = prepared.orderKey ?: return false
        val report = prepared.report ?: return false
        var applied = false
        session.withLaundrySession(owner, prepared.token, { true }) {
            vault.updateDeviceReportsIfBound(owner, binding) { records ->
                val current = records[key]
                if (current == null || !CloudDeviceReportPolicy.isCurrentReport(current, report)) records
                else {
                    applied = true
                    records + (key to CloudDeviceReportPolicy.acknowledged(current, report, receipt))
                }
            }
        }
        return applied
    }

    private fun commitDeviceFailure(owner: String, binding: CloudBinding, prepared: DevicePreparation,
        outcome: CloudSyncOutcome, transform: (CloudDeviceReportRecord) -> CloudDeviceReportRecord
    ): CloudSyncOutcome {
        val key = prepared.orderKey ?: return CloudSyncOutcome.NO_BINDING
        val report = prepared.report ?: return CloudSyncOutcome.NO_BINDING
        var applied = false
        session.withLaundrySession(owner, prepared.token, { true }) {
            vault.updateDeviceReportsIfBound(owner, binding) { records ->
                val current = records[key]
                if (current == null || !CloudDeviceReportPolicy.isCurrentReport(current, report)) records
                else {
                    applied = true
                    records + (key to transform(current))
                }
            }
        }
        if (applied) {
            // KEEP may have merged a newer order into this worker. A bad older item must not
            // strand another persisted transition when the app is no longer in the foreground.
            if (outcome == CloudSyncOutcome.REJECTED && deviceSessionCurrent(owner, binding, prepared.token) &&
                CloudDeviceReportQueue.next(vault.deviceReportRecords(owner).associateBy { it.orderKey }) != null)
                return CloudSyncOutcome.PENDING
            return outcome
        }
        if (!deviceSessionCurrent(owner, binding, prepared.token)) return CloudSyncOutcome.NO_BINDING
        // A stale failure must not block a changed deadline or a newer completion.
        return prepareDevice(owner, binding, false).outcome
    }

    /** Session order reads and cloud credential CAS follow the same lock order as local logout. */
    private fun prepare(owner: String, expected: CloudBinding?, force: Boolean): Preparation {
        if (owner.isBlank()) return Preparation(CloudSyncOutcome.NO_BINDING)
        val token = session.token()
        var preparation = Preparation(CloudSyncOutcome.NO_BINDING)
        session.withLaundrySession(owner, token, { true }) {
            val binding = vault.binding(owner)?.takeIf {
                it.owner == owner && it.consentToReadOnlyQuery && it.deviceToken.isNotBlank()
            } ?: return@withLaundrySession false
            if (expected != null && !binding.sameCredentialsAs(expected))
                return@withLaundrySession false
            if (binding.accountStatus == "auth_required") {
                preparation = Preparation(CloudSyncOutcome.AUTH_REQUIRED)
                return@withLaundrySession true
            }
            val candidate = CloudReminderPolicy.selectSingle(
                session.orders().filter { it.owner == owner },
                session.order()?.takeIf { it.owner == owner })
            if (candidate == null) {
                // A completed / no-longer-running order must not stay "pending" forever, but a
                // previous terminal rejection is kept as visible evidence of why no reminder was
                // created. A later explicit retry or a new order replaces it.
                var hadRejected = false
                val retained = vault.updateOrdersIfBound(owner, binding) { records ->
                    hadRejected = records.values.any { it.state == CloudOrderSyncState.REJECTED }
                    records.filterValues { it.state == CloudOrderSyncState.REJECTED }
                }
                if (retained) preparation = Preparation(
                    if (hadRejected) CloudSyncOutcome.REJECTED else CloudSyncOutcome.NO_ELIGIBLE_ORDER)
                return@withLaundrySession retained
            }
            val now = System.currentTimeMillis()
            var alreadySynced = false
            var rejected = false
            var queuedAttempts = 0
            val queued = vault.updateOrdersIfBound(owner, binding) { records ->
                val previous = records[candidate.orderKey]
                alreadySynced = previous?.state == CloudOrderSyncState.SYNCED
                // Automatic retries must not hammer a terminal 3xx/4xx rejection. The explicit
                // retry button passes force=true and gets one fresh attempt.
                rejected = previous?.state == CloudOrderSyncState.REJECTED && !force
                val priorAttempts = when {
                    force -> 0
                    previous?.state == CloudOrderSyncState.PENDING -> previous.attempts
                    else -> 0
                }
                queuedAttempts = priorAttempts
                val record = when {
                    alreadySynced -> requireNotNull(previous)
                    rejected -> requireNotNull(previous)
                    else -> CloudOrderSyncRecord(candidate.orderKey, candidate.orderId,
                        candidate.orderNo, CloudOrderSyncState.PENDING, now, "queued",
                        priorAttempts)
                }
                mapOf(candidate.orderKey to record)
            }
            if (queued) preparation = when {
                alreadySynced -> Preparation(CloudSyncOutcome.ALREADY_SYNCED)
                rejected -> Preparation(CloudSyncOutcome.REJECTED)
                else -> Preparation(CloudSyncOutcome.PENDING,
                    QueuedOrder(token, binding, candidate, now, queuedAttempts))
            }
            queued
        }
        return preparation
    }

    /** Never recreate a binding or an order key removed while the HTTP request was in flight. */
    private fun commit(queued: QueuedOrder, state: CloudOrderSyncState,
        outcome: CloudSyncOutcome, issue: String?, attempts: Int = 0,
        reasonCode: String? = null
    ): CloudSyncOutcome {
        val owner = queued.binding.owner
        var currentOutcome = outcome
        val committed = session.withLaundrySession(owner, queued.token, { true }) {
            vault.updateOrdersIfBound(owner, queued.binding) { records ->
                if (queued.candidate.orderKey !in records) {
                    // KEEP may have coalesced the new order's enqueue into this running worker.
                    currentOutcome = when {
                        records.values.any { it.state == CloudOrderSyncState.PENDING } ->
                            CloudSyncOutcome.PENDING
                        records.values.any { it.state == CloudOrderSyncState.REJECTED } ->
                            CloudSyncOutcome.REJECTED
                        else -> CloudSyncOutcome.NO_ELIGIBLE_ORDER
                    }
                    records
                } else records + (queued.candidate.orderKey to CloudOrderSyncRecord(
                    queued.candidate.orderKey, queued.candidate.orderId, queued.candidate.orderNo,
                    state, queued.attemptedAtMs, issue, attempts, reasonCode))
            }
        }
        if (committed) return currentOutcome
        // A replacement binding may have enqueued while this worker was running. KEEP can merge
        // that enqueue into this worker, so leave a durable retry for the current pending work.
        // This is a scheduling decision only: the old response never writes the new binding.
        var retryCurrent = false
        val currentToken = session.token()
        session.withLaundrySession(owner, currentToken, { true }) {
            val currentBinding = vault.binding(owner) ?: return@withLaundrySession false
            vault.withBinding(owner, currentBinding) { current ->
                retryCurrent = current.orders.values.any { it.state == CloudOrderSyncState.PENDING }
                true
            } == true
        }
        return if (retryCurrent) CloudSyncOutcome.PENDING else CloudSyncOutcome.NO_BINDING
    }

    /** Retry only encrypted logout records that were detached before the local session was cleared. */
    suspend fun retryPendingUnbinds(owner: String): CloudUnbindOutcome {
        val pending = vault.pendingUnbinds(owner)
        var succeeded = 0
        for (record in pending) {
            try {
                api.unbind(record.serverUrl, record.deviceToken)
                vault.removePendingUnbind(owner, record.deviceId)
                succeeded++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                vault.markUnbindAttempt(owner, record.deviceId, "unbind_failed")
            }
        }
        return CloudUnbindOutcome(pending.size, succeeded, pending.size - succeeded)
    }

    /** Worker logout path: no live session and no owner string is needed. */
    suspend fun retryPendingUnbindsHashed(ownerHash: String): CloudUnbindOutcome {
        val pending = vault.pendingUnbindsHashed(ownerHash)
        var succeeded = 0
        for (record in pending) {
            try {
                api.unbind(record.serverUrl, record.deviceToken)
                vault.removePendingUnbindHashed(ownerHash, record.deviceId)
                succeeded++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                vault.markUnbindAttemptHashed(ownerHash, record.deviceId, "unbind_failed")
            }
        }
        return CloudUnbindOutcome(pending.size, succeeded, pending.size - succeeded)
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun ownerLock(owner: String): Mutex = locks.computeIfAbsent(owner) { Mutex() }
    }
}
