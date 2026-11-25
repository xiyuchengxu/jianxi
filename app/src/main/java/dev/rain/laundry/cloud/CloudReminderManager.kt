package dev.rain.laundry.cloud

import android.content.Context
import android.os.Build
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CloudLogoutResult { NO_BINDING, CONFIRMED, UNCONFIRMED }

/** All result checks and local commits are serialized with logout, including same-account logins. */
class CloudReminderManager(context: Context) {
    private val appContext = context.applicationContext
    private val session = SessionVault(appContext)
    private val vault = CloudReminderVault(appContext)
    private val operations = CloudReminderOperations(appContext)
    private val api = CloudReminderApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guard = CloudReminderGuard()
    private val _state = MutableStateFlow(CloudReminderUiState())
    val state: StateFlow<CloudReminderUiState> = _state.asStateFlow()

    private class Operation(val stamp: CloudOperationStamp, val token: String,
        val binding: CloudBinding?) {
        val owner: String get() = stamp.session.owner
    }

    fun onSessionAvailable(owner: String) {
        val currentOwner = owner.takeIf { it.isNotBlank() && session.owner() == it }.orEmpty()
        guard.reset(currentOwner) {
            if (currentOwner.isBlank()) {
                _state.value = emptyState()
                return@reset
            }
            val binding = runCatching { vault.binding(owner) }.getOrNull()
            val local = runCatching { vault.orderRecords(owner) }.getOrDefault(emptyList())
            val reports = runCatching { vault.deviceReportRecords(owner) }.getOrDefault(emptyList())
            val device = binding?.deviceAssisted == true
            val issue = if (device) deviceIssue(reports)
            else CloudReminderStateRules.rejectedIssue(local)
                ?: CloudReminderStateRules.pendingIssue(local)
            _state.value = CloudReminderUiState(
                status = statusFor(binding, local, issue, reports), bound = binding != null,
                serverUrl = binding?.serverUrl ?: CloudReminderEndpoint.DEFAULT_SERVER_URL,
                accountId = binding?.accountId, deviceLabel = binding?.deviceLabel,
                notificationConfigured = binding?.notificationConfigured == true,
                accountStatus = binding?.accountStatus.orEmpty(),
                deviceAssisted = device, deviceReport = displayDeviceReport(owner, reports),
                pendingSync = syncPending(binding, local, reports),
                issue = issue,
                clearSecretsToken = _state.value.clearSecretsToken + 1)
            if (runCatching { vault.pendingUnbinds(owner).isNotEmpty() }.getOrDefault(false))
                CloudReminderWork.requestUnbind(appContext, owner)
            if (binding != null) CloudReminderWork.requestSync(appContext, owner)
        }
    }

    fun refreshLocal(owner: String) {
        val stamp = guard.session(owner) ?: return
        guard.withCurrent(stamp) {
            if (session.owner() != owner) return@withCurrent
            val binding = runCatching { vault.binding(owner) }.getOrNull()
            val local = runCatching { vault.orderRecords(owner) }.getOrDefault(emptyList())
            val reports = runCatching { vault.deviceReportRecords(owner) }.getOrDefault(emptyList())
            val device = binding?.deviceAssisted == true
            val previous = _state.value
            val issue = if (device) deviceIssue(reports)
            else CloudReminderStateRules.rejectedIssue(local)
                ?: CloudReminderStateRules.pendingIssue(local)
                ?: previous.issue?.takeIf {
                    it.kind != CloudIssueKind.SYNC_PENDING &&
                        it.kind != CloudIssueKind.SUBSCRIPTION_REJECTED &&
                        it.kind != CloudIssueKind.DEVICE_REPORT_REJECTED &&
                        it.kind != CloudIssueKind.VERSION_UNSUPPORTED
                }
            _state.value = previous.copy(
                status = statusFor(binding, local, issue, reports), bound = binding != null,
                serverUrl = binding?.serverUrl ?: previous.serverUrl,
                accountId = binding?.accountId, deviceLabel = binding?.deviceLabel,
                notificationConfigured = binding?.notificationConfigured == true,
                accountStatus = binding?.accountStatus.orEmpty(),
                deviceAssisted = device, deviceReport = displayDeviceReport(owner, reports),
                pendingSync = syncPending(binding, local, reports), issue = issue)
        }
    }

    /** Queue is local and atomic; WorkManager retains the retry when the process exits. */
    fun onOrderSaved(owner: String) = requestAutoSync(owner)

    fun requestAutoSync(owner: String) {
        val stamp = guard.session(owner) ?: return
        scope.launch {
            guard.withCurrent(stamp) {
                if (session.owner() != owner) return@withCurrent
                val binding = runCatching { vault.binding(owner) }.getOrNull() ?: return@withCurrent
                // Platform verification depends on the stored platform token; device_report uses
                // only the device Bearer and must still be allowed while platform login is stale.
                if (!binding.deviceAssisted && binding.accountStatus == "auth_required")
                    return@withCurrent
                val outcome = runCatching { operations.queue(owner, binding) }.getOrNull()
                    ?: return@withCurrent
                refreshLocal(owner)
                if (outcome == CloudSyncOutcome.PENDING)
                    CloudReminderWork.requestSync(appContext, owner)
            }
        }
    }

    fun openSettings(owner: String) = refreshStatus(owner)

    /**
     * Explicit opt-in only. This is a local encrypted mode switch; no report is sent until a
     * current running snapshot exists. Changing the routing mode also invalidates any in-flight
     * platform reply because deviceAssisted is part of the binding credential/CAS key.
     */
    fun enableDeviceAssisted(owner: String) {
        val stamp = guard.session(owner) ?: return
        guard.withCurrent(stamp) {
            if (session.owner() != owner || _state.value.busy) return@withCurrent
            val binding = runCatching { vault.binding(owner) }.getOrNull()
                ?: return@withCurrent
            if (binding.deviceAssisted) return@withCurrent
            val updated = binding.copy(deviceAssisted = true)
            if (!vault.compareAndSetBinding(owner, binding, updated,
                    orders = emptyMap(), deviceReports = emptyMap())) return@withCurrent
            _state.value = _state.value.copy(
                deviceAssisted = true, deviceReport = null, issue = null,
                pendingSync = false, status = CloudReminderStatus.BOUND)
            CloudReminderWork.requestSync(appContext, owner)
        }
    }

    fun bind(owner: String, rawServerUrl: String, pairingCode: String, miaotixingCode: String,
        consent: Boolean
    ) {
        if (!consent) { publishIssue(owner, CloudIssue(CloudIssueKind.CONSENT_REQUIRED)); return }
        if (pairingCode.isBlank()) {
            publishIssue(owner, CloudIssue(CloudIssueKind.PAIRING_REJECTED)); return
        }
        if (miaotixingCode.isBlank()) {
            publishIssue(owner, CloudIssue(CloudIssueKind.NOT_CONFIGURED)); return
        }
        val normalized = try { CloudReminderEndpoint.normalize(rawServerUrl) }
        catch (_: CloudInvalidUrl) {
            publishIssue(owner, CloudIssue(CloudIssueKind.INVALID_URL)); return
        }
        val operation = begin(owner, requireBinding = false) ?: return
        scope.launch {
            var issued: CloudBinding? = null
            var adopted = false
            try {
                val result = api.bind(normalized, pairingCode.trim(), operation.token,
                    miaotixingCode.trim(), deviceLabel())
                val binding = CloudBinding(owner, normalized, result.deviceToken, result.accountId,
                    result.deviceId, deviceLabel(), notificationConfigured = true)
                issued = binding
                var pending = false
                commit(operation) {
                    // Save the binding and the current order's PENDING record together. No running
                    // order means a plain BOUND state and no fictitious pending subscription.
                    val current = session.order()?.takeIf { it.owner == owner }
                    val candidate = CloudReminderPolicy.selectSingle(
                        session.orders().filter { it.owner == owner }, current)
                    val records = candidate?.let {
                        pending = true
                        mapOf(it.orderKey to CloudOrderSyncRecord(it.orderKey, it.orderId,
                            it.orderNo, CloudOrderSyncState.PENDING, System.currentTimeMillis(), "queued"))
                    }.orEmpty()
                    if (!vault.compareAndSetBinding(owner, null, binding, records,
                            emptyMap())) return@commit
                    adopted = true
                    _state.value = CloudReminderUiState(
                        status = if (pending) CloudReminderStatus.PENDING else CloudReminderStatus.BOUND,
                        bound = true, serverUrl = normalized, accountId = binding.accountId,
                        deviceLabel = binding.deviceLabel, notificationConfigured = true,
                        accountStatus = "active", pendingSync = pending,
                        clearSecretsToken = _state.value.clearSecretsToken + 1)
                    if (pending) CloudReminderWork.requestSync(appContext, owner)
                }
                if (adopted && pending) {
                    val boundOperation = Operation(operation.stamp, operation.token, binding)
                    try {
                        finishSync(boundOperation, operations.sync(owner, binding))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        handleBindingFailure(boundOperation, failure)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleBindingFailure(operation, failure)
            } finally {
                // Even a successful bind whose reply belongs to an old login must be revoked.
                val orphan = issued
                if (!adopted && orphan != null) revokeOrQueue(orphan)
            }
        }
    }

    fun refreshStatus(owner: String) {
        val operation = begin(owner) ?: return
        val binding = operation.binding ?: return
        scope.launch {
            try {
                val remote = api.status(binding.serverUrl, binding.deviceToken)
                commit(operation) { stored ->
                    val updated = binding.copy(notificationConfigured = remote.notificationConfigured,
                        accountStatus = remote.accountStatus)
                    if (!vault.compareAndSetBinding(owner, binding, updated)) return@commit
                    val device = updated.deviceAssisted
                    val merged = stored.deviceReports.mapValues { (_, report) ->
                        mergeDeviceDisplay(report, remote.subscriptions)
                    }
                    if (device && merged != stored.deviceReports)
                        vault.updateDeviceReportsIfBound(owner, updated) { merged }
                    val reports = merged.values.toList()
                    // A stale platform token pauses only platform verification. device_report uses
                    // its own device Bearer and remains reportable.
                    val issue = if (!device && remote.accountStatus == "auth_required")
                        CloudIssue(CloudIssueKind.AUTH_REQUIRED)
                    else if (device) deviceIssue(reports)
                    else CloudReminderStateRules.rejectedIssue(stored.orders.values)
                        ?: CloudReminderStateRules.pendingIssue(stored.orders.values)
                    _state.value = _state.value.copy(
                        status = statusFor(updated, stored.orders.values.toList(), issue, reports),
                        notificationConfigured = updated.notificationConfigured,
                        accountStatus = updated.accountStatus, subscriptions = remote.subscriptions,
                        deviceAssisted = device, deviceReport = displayDeviceReport(owner, reports),
                        pendingSync = syncPending(updated, stored.orders.values.toList(), reports),
                        busy = false, issue = issue)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { handleBindingFailure(operation, failure, refresh = true) }
        }
    }

    fun retrySync(owner: String) {
        val operation = begin(owner) ?: return
        scope.launch {
            try {
                val outcome = operations.sync(owner, operation.binding, force = true)
                finishSync(operation, outcome)
                // A manual retry that hits a transient failure still returns to the durable
                // automatic recovery path instead of leaving only a pending local record.
                if (outcome == CloudSyncOutcome.PENDING)
                    CloudReminderWork.requestSync(appContext, owner)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { handleBindingFailure(operation, failure) }
        }
    }

    private fun finishSync(operation: Operation, outcome: CloudSyncOutcome) {
        commit(operation) { stored ->
            val local = stored.orders.values
            val reports = stored.deviceReports.values.toList()
            val device = stored.binding?.deviceAssisted == true
            val deviceReport = displayDeviceReport(operation.owner, reports)
            val issue = if (device) deviceIssue(reports) ?: when (outcome) {
                CloudSyncOutcome.PENDING -> deviceReport?.takeIf {
                    it.lastIssue != null && it.lastIssue != "queued"
                }?.let { CloudIssue(CloudIssueKind.SYNC_PENDING,
                    CloudSyncFailurePolicy.httpCode(it.lastIssue),
                    CloudUpstream.sanitize(it.reasonCode)) }
                CloudSyncOutcome.AUTH_REQUIRED -> CloudIssue(CloudIssueKind.AUTH_REQUIRED,
                    reasonCode = CloudUpstream.sanitize(deviceReport?.reasonCode))
                CloudSyncOutcome.REJECTED -> CloudIssue(CloudIssueKind.DEVICE_REPORT_REJECTED,
                    CloudSyncFailurePolicy.httpCode(deviceReport?.lastIssue),
                    CloudUpstream.sanitize(deviceReport?.reasonCode))
                CloudSyncOutcome.UNSUPPORTED -> CloudIssue(CloudIssueKind.VERSION_UNSUPPORTED)
                else -> null
            } else when (outcome) {
                CloudSyncOutcome.PENDING -> CloudReminderStateRules.pendingIssue(local)
                CloudSyncOutcome.AUTH_REQUIRED -> local.firstOrNull {
                    it.state == CloudOrderSyncState.PENDING
                }?.let { CloudIssue(CloudIssueKind.AUTH_REQUIRED,
                    reasonCode = CloudUpstream.sanitize(it.reasonCode)) }
                    ?: CloudIssue(CloudIssueKind.AUTH_REQUIRED)
                CloudSyncOutcome.REJECTED -> CloudReminderStateRules.rejectedIssue(local)
                    ?: CloudIssue(CloudIssueKind.SUBSCRIPTION_REJECTED)
                else -> null
            }
            _state.value = _state.value.copy(
                status = statusFor(stored.binding, local.toList(), issue, reports),
                deviceAssisted = device, deviceReport = deviceReport,
                pendingSync = syncPending(stored.binding, local.toList(), reports),
                busy = false, issue = issue)
        }
    }

    fun updateNotificationCode(owner: String, code: String) {
        if (code.isBlank()) { publishIssue(owner, CloudIssue(CloudIssueKind.NOT_CONFIGURED)); return }
        val operation = begin(owner) ?: return
        val binding = operation.binding ?: return
        scope.launch {
            try {
                api.updateNotification(binding.serverUrl, binding.deviceToken, code.trim())
                commit(operation) { stored ->
                    val updated = binding.copy(notificationConfigured = true)
                    if (!vault.compareAndSetBinding(owner, binding, updated)) return@commit
                    val reports = stored.deviceReports.values.toList()
                    val device = updated.deviceAssisted
                    val issue = if (device) deviceIssue(reports)
                    else CloudReminderStateRules.rejectedIssue(stored.orders.values)
                        ?: CloudReminderStateRules.pendingIssue(stored.orders.values)
                    _state.value = _state.value.copy(busy = false, issue = issue,
                        notificationConfigured = true,
                        status = statusFor(updated, stored.orders.values.toList(), issue, reports),
                        deviceAssisted = device, deviceReport = displayDeviceReport(owner, reports),
                        pendingSync = syncPending(updated, stored.orders.values.toList(), reports),
                        clearSecretsToken = _state.value.clearSecretsToken + 1)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { handleBindingFailure(operation, failure) }
        }
    }

    fun reauthorize(owner: String) {
        val operation = begin(owner) ?: return
        val binding = operation.binding ?: return
        scope.launch {
            try {
                api.reauthorize(binding.serverUrl, binding.deviceToken, operation.token)
                val accepted = commit(operation) { stored ->
                    val updated = binding.copy(accountStatus = "active")
                    if (!vault.compareAndSetBinding(owner, binding, updated)) return@commit
                    val reports = stored.deviceReports.values.toList()
                    val device = updated.deviceAssisted
                    val issue = if (device) deviceIssue(reports)
                    else CloudReminderStateRules.rejectedIssue(stored.orders.values)
                        ?: CloudReminderStateRules.pendingIssue(stored.orders.values)
                    _state.value = _state.value.copy(busy = false, issue = issue,
                        accountStatus = "active",
                        deviceAssisted = device, deviceReport = displayDeviceReport(owner, reports),
                        pendingSync = syncPending(updated, stored.orders.values.toList(), reports),
                        status = statusFor(updated, stored.orders.values.toList(), issue, reports))
                    CloudReminderWork.requestSync(appContext, owner)
                }
                if (accepted) refreshStatus(owner)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { handleBindingFailure(operation, failure) }
        }
    }

    fun testNotification(owner: String) {
        val operation = begin(owner, clearTest = true) ?: return
        val binding = operation.binding ?: return
        scope.launch {
            try {
                val result = api.testNotification(binding.serverUrl, binding.deviceToken)
                commit(operation) {
                    _state.value = _state.value.copy(busy = false, issue = null, testResult = result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (rate: CloudRateLimited) {
                commit(operation) {
                    _state.value = _state.value.copy(busy = false,
                        cooldownUntilMs = System.currentTimeMillis() + rate.retryAfterSeconds * 1000L,
                        issue = CloudIssue(CloudIssueKind.RATE_LIMITED, rate.retryAfterSeconds.toString()))
                }
            } catch (failure: Exception) { handleBindingFailure(operation, failure) }
        }
    }

    fun unbind(owner: String) {
        val operation = begin(owner) ?: return
        val binding = operation.binding ?: return
        scope.launch {
            try {
                api.unbind(binding.serverUrl, binding.deviceToken)
                commit(operation) {
                    if (!vault.compareAndSetBinding(owner, binding, null, emptyMap(),
                            emptyMap())) return@commit
                    vault.removePendingUnbind(owner, binding.deviceId)
                    _state.value = emptyState()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Failure belongs only to the original operation and its unchanged binding.
                commit(operation) {
                    val issue = if (failure is CloudAuthRequired)
                        CloudIssue(CloudIssueKind.AUTH_REQUIRED) else CloudIssue(CloudIssueKind.UNBIND_FAILED)
                    _state.value = _state.value.copy(busy = false, issue = issue,
                        status = if (failure is CloudAuthRequired) CloudReminderStatus.AUTH_REQUIRED
                        else CloudReminderStatus.ERROR)
                }
            }
        }
    }

    fun onLogout(owner: String, onResult: (CloudLogoutResult) -> Unit) {
        var hadBinding = false
        var pending: CloudPendingUnbind? = null
        lateinit var logoutStamp: CloudSessionStamp
        guard.reset("") {
            // Invalidate every old result before detaching the binding under the same guard.
            hadBinding = owner.isNotBlank() && runCatching { vault.binding(owner) }.getOrNull() != null
            pending = runCatching { vault.detachForLogout(owner) }.getOrNull()
            _state.value = emptyState()
            logoutStamp = guard.snapshot()
        }
        val record = pending
        fun report(result: CloudLogoutResult) { guard.withCurrent(logoutStamp) { onResult(result) } }
        if (record == null) {
            report(if (hadBinding) CloudLogoutResult.UNCONFIRMED else CloudLogoutResult.NO_BINDING)
            return
        }
        CloudReminderWork.requestUnbind(appContext, owner)
        scope.launch {
            try {
                api.unbind(record.serverUrl, record.deviceToken)
                vault.removePendingUnbind(owner, record.deviceId)
                report(CloudLogoutResult.CONFIRMED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val stillPending = runCatching {
                    vault.pendingUnbinds(owner).any { it.deviceId == record.deviceId }
                }.getOrDefault(true)
                if (stillPending) {
                    runCatching { vault.markUnbindAttempt(owner, record.deviceId, "unbind_failed") }
                    report(CloudLogoutResult.UNCONFIRMED)
                } else report(CloudLogoutResult.CONFIRMED)
            }
        }
    }

    fun resetForLoggedOut() { guard.reset("") { _state.value = emptyState() } }

    /** Never hold this guard over a network call. Lock order is guard -> session -> cloud vault. */
    private fun begin(owner: String, requireBinding: Boolean = true, clearTest: Boolean = false): Operation? {
        val sessionStamp = guard.session(owner) ?: return null
        return guard.withCurrent(sessionStamp) {
            if (session.owner() != owner || _state.value.busy) return@withCurrent null
            val token = session.token()
            if (token.isBlank()) {
                publishIssue(owner, CloudIssue(CloudIssueKind.LOGIN_REQUIRED))
                return@withCurrent null
            }
            val binding = runCatching { vault.binding(owner) }.getOrNull()
            if (requireBinding != (binding != null)) return@withCurrent null
            val stamp = guard.begin(owner) {
                _state.value = _state.value.copy(busy = true, issue = null,
                    testResult = if (clearTest) null else _state.value.testResult)
            } ?: return@withCurrent null
            Operation(stamp, token, binding)
        }
    }

    private fun commit(operation: Operation, block: (CloudStoredState) -> Unit): Boolean =
        guard.withCurrent(operation.stamp) {
            session.withLaundrySession(operation.owner, operation.token, { true }) {
                vault.withBinding(operation.owner, operation.binding) { stored -> block(stored); true } == true
            }
        } == true

    private suspend fun revokeOrQueue(binding: CloudBinding) {
        val pending = CloudPendingUnbind(binding.owner, binding.serverUrl, binding.deviceToken,
            binding.accountId, binding.deviceId, System.currentTimeMillis(), lastError = "stale_bind")
        runCatching { vault.addPendingUnbind(binding.owner, pending) }
        CloudReminderWork.requestUnbind(appContext, binding.owner)
        try {
            api.unbind(binding.serverUrl, binding.deviceToken)
            vault.removePendingUnbind(binding.owner, binding.deviceId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The encrypted record remains for WorkManager. */ }
    }

    private fun handleBindingFailure(operation: Operation, failure: Exception, refresh: Boolean = false) {
        val issue = when (failure) {
            is CloudPairingRejected -> CloudIssue(CloudIssueKind.PAIRING_REJECTED)
            is CloudAuthRequired -> CloudIssue(CloudIssueKind.AUTH_REQUIRED)
            is CloudRateLimited -> CloudIssue(CloudIssueKind.RATE_LIMITED, failure.retryAfterSeconds.toString())
            is CloudNetworkUnavailable -> CloudIssue(CloudIssueKind.NETWORK)
            is CloudProtocolMismatch -> CloudIssue(CloudIssueKind.INVALID_RESPONSE)
            is CloudInvalidUrl -> CloudIssue(CloudIssueKind.INVALID_URL)
            is CloudRejected -> CloudIssue(CloudIssueKind.SERVER_REJECTED)
            else -> CloudIssue(if (refresh) CloudIssueKind.NETWORK else CloudIssueKind.GENERIC)
        }
        commit(operation) { stored ->
            val local = stored.orders.values.toList()
            val reports = stored.deviceReports.values.toList()
            _state.value = _state.value.copy(busy = false, issue = issue,
                deviceAssisted = stored.binding?.deviceAssisted == true,
                deviceReport = displayDeviceReport(operation.owner, reports),
                pendingSync = syncPending(stored.binding, local, reports),
                status = statusFor(stored.binding, local, issue, reports))
        }
    }

    private fun publishIssue(owner: String, issue: CloudIssue) {
        val stamp = guard.session(owner) ?: return
        guard.withCurrent(stamp) {
            if (session.owner() != owner || _state.value.busy) return@withCurrent
            val previous = _state.value
            _state.value = previous.copy(issue = issue,
                status = CloudReminderStateRules.status(previous.bound, previous.accountStatus,
                    previous.pendingSync, issue))
        }
    }

    private fun emptyState() = CloudReminderUiState(clearSecretsToken = _state.value.clearSecretsToken + 1)

    private fun syncPending(binding: CloudBinding?, local: List<CloudOrderSyncRecord>,
        reports: List<CloudDeviceReportRecord>
    ): Boolean = if (binding?.deviceAssisted == true)
        reports.any { !it.blocked && !it.unsupported &&
            CloudDeviceReportPolicy.pendingState(it) != null }
    else local.any { it.state == CloudOrderSyncState.PENDING }

    private fun mergeDeviceDisplay(report: CloudDeviceReportRecord,
        subscriptions: List<CloudSubscriptionSummary>
    ): CloudDeviceReportRecord {
        val match = subscriptions.firstOrNull {
            it.source == "device_report" && CloudDeviceReportPolicy.matchesSubscription(report, it)
        } ?: return report
        return report.copy(
            serverState = match.state,
            serverSource = match.source,
            serverClientState = match.clientState,
            completionSource = match.completionSource,
            estimatedDeliveryState = match.estimatedDeliveryState,
            estimatedAccepted = match.estimatedAccepted,
            estimatedDelivered = match.estimatedDelivered
        )
    }

    private fun displayDeviceReport(owner: String, reports: List<CloudDeviceReportRecord>): CloudDeviceReportRecord? {
        val currentKey = session.order()?.takeIf { it.owner == owner && it.isLaundry }?.let(OrderHistory::key)
        return CloudDeviceReportQueue.display(reports, currentKey)
    }

    private fun deviceIssue(reports: List<CloudDeviceReportRecord>): CloudIssue? =
        CloudDeviceReportQueue.issue(reports)

    private fun statusFor(binding: CloudBinding?, local: List<CloudOrderSyncRecord>,
        issue: CloudIssue?, reports: List<CloudDeviceReportRecord> = emptyList()
    ) = CloudReminderStateRules.status(binding != null,
        if (binding?.deviceAssisted == true) "" else binding?.accountStatus.orEmpty(),
        syncPending(binding, local, reports), issue)

    private fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
        .filter { it.code in 32..126 || it.code in 0x4e00..0x9fff }
        .trim().take(80).ifBlank { "Android device" }
}
