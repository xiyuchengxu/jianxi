package dev.rain.laundry.ui

import dev.rain.laundry.R
import dev.rain.laundry.cloud.CloudDeviceReportPolicy
import dev.rain.laundry.cloud.CloudDeviceReportRecord
import dev.rain.laundry.cloud.CloudIssue
import dev.rain.laundry.cloud.CloudIssueKind
import dev.rain.laundry.cloud.CloudReminderStatus
import dev.rain.laundry.cloud.CloudReminderUiState
import dev.rain.laundry.cloud.CloudTestStatus
import dev.rain.laundry.i18n.appText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import dev.rain.laundry.ui.ControlIcon as Icon
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Opt-in card inside the existing laundry notification screen.
 *
 * Pairing code and 喵码 are held only as transient Compose state. They are never put in
 * rememberSaveable, stored in the vault, or shown after a successful request.
 */
@Composable
internal fun CloudReminderSettings(
    state: CloudReminderUiState,
    loggedIn: Boolean,
    onBind: (serverUrl: String, pairingCode: String, miaotixingCode: String, consent: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onUnbind: () -> Unit,
    onReauthorize: () -> Unit,
    onUpdateCode: (String) -> Unit,
    onTest: () -> Unit,
    /** Parent wires this to CloudReminderManager.enableDeviceAssisted(owner). */
    onEnableDeviceAssisted: () -> Unit = {}
) {
    var serverUrl by rememberSaveable { mutableStateOf(state.serverUrl) }
    var pairingCode by remember { mutableStateOf("") }
    var miaotixingCode by remember { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    var updateCode by remember { mutableStateOf("") }
    var unbindOpen by remember { mutableStateOf(false) }
    var lastSecretsToken by remember { mutableLongStateOf(state.clearSecretsToken) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val cooldownSeconds = ((state.cooldownUntilMs - now).coerceAtLeast(0) / 1000L).toInt()

    LaunchedEffect(state.serverUrl) {
        if (!state.bound && state.serverUrl.isNotBlank()) serverUrl = state.serverUrl
    }
    LaunchedEffect(state.clearSecretsToken) {
        if (state.clearSecretsToken != lastSecretsToken) {
            lastSecretsToken = state.clearSecretsToken
            pairingCode = ""
            miaotixingCode = ""
            updateCode = ""
            updateOpen = false
            consent = false
        }
    }
    LaunchedEffect(state.cooldownUntilMs) {
        while (System.currentTimeMillis() < state.cooldownUntilMs) {
            delay(1000)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }

    AppCard(controlId = "notifications.cloud", shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(appText(R.string.cloud_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(appText(R.string.cloud_subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(onClick = {}, enabled = false,
                    label = { Text(statusText(state.status)) })
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(appText(R.string.cloud_voluntary), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!state.bound) {
                if (!loggedIn) Text(appText(R.string.cloud_login_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
                AppOutlinedTextField(serverUrl, { serverUrl = it.take(2048) },
                    labelText = appText(R.string.cloud_server_url),
                    label = { Text(appText(R.string.cloud_server_url)) },
                    singleLine = true, enabled = !state.busy && loggedIn,
                    supportingText = { Text(appText(R.string.cloud_server_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                AppOutlinedTextField(pairingCode, { pairingCode = it.take(512) },
                    labelText = appText(R.string.cloud_pairing_code),
                    label = { Text(appText(R.string.cloud_pairing_code)) },
                    singleLine = true, enabled = !state.busy && loggedIn,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                AppOutlinedTextField(miaotixingCode, { miaotixingCode = it.take(512) },
                    labelText = appText(R.string.cloud_miaotixing_code),
                    label = { Text(appText(R.string.cloud_miaotixing_code)) },
                    singleLine = true, enabled = !state.busy && loggedIn,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text(appText(R.string.cloud_miaotixing_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    AppCheckbox(consent, { consent = it }, enabled = !state.busy && loggedIn,
                        modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appText(R.string.cloud_consent), style = MaterialTheme.typography.bodySmall)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f).controlContainer(),
                    shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(appText(R.string.cloud_security_note),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                AppButton(onClick = {
                    onBind(serverUrl.trim(), pairingCode, miaotixingCode, consent)
                }, enabled = loggedIn && !state.busy && serverUrl.isNotBlank() &&
                    pairingCode.isNotBlank() && miaotixingCode.isNotBlank() && consent,
                    modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(appText(R.string.cloud_bind))
                }
                state.issue?.let { CloudIssueNotice(it) }
            } else {
                Text(appText(R.string.cloud_bound_server, state.serverUrl),
                    style = MaterialTheme.typography.bodyMedium, localize = false)
                if (!state.deviceAssisted) {
                    Text(appText(R.string.cloud_device_assist_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppButton(onClick = onEnableDeviceAssisted, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(appText(R.string.cloud_device_assist_enable))
                    }
                    Text(appText(R.string.cloud_device_assist_unbind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(appText(R.string.cloud_device_assist_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    DeviceAssistNotice(state.deviceReport)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, enabled = false,
                        label = { Text(if (state.notificationConfigured)
                            appText(R.string.cloud_notification_configured)
                        else appText(R.string.cloud_notification_missing)) })
                    if (state.pendingSync) AssistChip(onClick = {}, enabled = false,
                        label = { Text(appText(R.string.cloud_status_pending)) })
                }
                if (state.pendingSync) Text(appText(R.string.cloud_pending_sync),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                if (state.accountStatus == "auth_required") Text(appText(R.string.cloud_auth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                val active = state.subscriptions.count { it.state == "active" }
                val completed = state.subscriptions.count { it.state == "completed" }
                if (state.subscriptions.isEmpty()) Text(appText(R.string.cloud_no_subscriptions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(appText(R.string.cloud_subscription_summary, active, completed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val accepted = state.subscriptions.any { it.accepted || it.deliveryState == "accepted" }
                val unknown = state.subscriptions.any { it.deliveryState == "unknown" }
                if (accepted) Text(appText(R.string.cloud_delivery_accepted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (unknown) Text(appText(R.string.cloud_delivery_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = onRefresh, enabled = !state.busy,
                        modifier = Modifier.weight(1f)) {
                        Text(appText(R.string.cloud_refresh))
                    }
                    val retryableIssue = state.issue?.kind in setOf(
                        CloudIssueKind.NETWORK, CloudIssueKind.SUBSCRIPTION_REJECTED,
                        CloudIssueKind.DEVICE_REPORT_REJECTED,
                        CloudIssueKind.VERSION_UNSUPPORTED)
                    if (state.pendingSync || retryableIssue)
                        AppOutlinedButton(onClick = onRetry, enabled = !state.busy,
                            modifier = Modifier.weight(1f)) {
                            Text(appText(R.string.cloud_retry))
                        }
                    if (state.status == CloudReminderStatus.AUTH_REQUIRED)
                        AppOutlinedButton(onClick = onReauthorize, enabled = !state.busy,
                            modifier = Modifier.weight(1f)) {
                            Text(appText(R.string.cloud_reauthorize))
                        }
                }
                AppOutlinedButton(onClick = {
                    onTest()
                }, enabled = !state.busy && System.currentTimeMillis() >= state.cooldownUntilMs,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (cooldownSeconds > 0)
                        appText(R.string.cloud_test_cooldown, cooldownSeconds)
                    else appText(R.string.cloud_test))
                }
                state.testResult?.let { TestResultNotice(it.status) }

                if (updateOpen) {
                    AppOutlinedTextField(updateCode, { updateCode = it.take(512) },
                        labelText = appText(R.string.cloud_update_code_label),
                        label = { Text(appText(R.string.cloud_update_code_label)) },
                        singleLine = true, enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppOutlinedButton(onClick = { updateOpen = false; updateCode = "" },
                            enabled = !state.busy) { Text(appText(R.string.cloud_cancel)) }
                        AppButton(onClick = { onUpdateCode(updateCode) },
                            enabled = !state.busy && updateCode.isNotBlank()) {
                            Text(appText(R.string.cloud_save))
                        }
                    }
                } else AppOutlinedButton(onClick = { updateOpen = true }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(appText(R.string.cloud_update_code))
                }

                state.issue?.let { CloudIssueNotice(it) }
                AppTextButton(onClick = { unbindOpen = true }, enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(appText(R.string.cloud_unbind))
                }
            }
        }
    }

    if (unbindOpen) AlertDialog(onDismissRequest = { unbindOpen = false },
        title = { Text(appText(R.string.cloud_unbind_confirm_title)) },
        text = { Text(appText(R.string.cloud_unbind_confirm_text)) },
        confirmButton = { AppTextButton(onClick = {
            unbindOpen = false
            onUnbind()
        }) { Text(appText(R.string.cloud_unbind)) } },
        dismissButton = { AppTextButton(onClick = { unbindOpen = false }) {
            Text(appText(R.string.cloud_cancel))
        } })
}

/**
 * Only explicit phone-assisted evidence/readback is shown. A server `accepted` is an estimate
 * acceptance, never a delivered claim; an unknown deadline is shown as unknown, not invented.
 */
@Composable
private fun DeviceAssistNotice(report: CloudDeviceReportRecord?) {
    if (report == null) return
    val pending = CloudDeviceReportPolicy.pendingState(report)
    val expected = report.runningExpectedEndAtMs
    val expectedText = expected?.let {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT).format(java.util.Date(it))
    }
    when {
        report.unsupported -> Text(appText(R.string.cloud_device_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        report.blocked -> Text(appText(R.string.cloud_device_rejected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        pending == "running" -> Text(appText(R.string.cloud_device_assist_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        pending == "completed" || pending == "cancelled" ->
            Text(appText(R.string.cloud_device_assist_pending_terminal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        report.cancelledAcked ||
            (report.serverState == "cancelled" && report.serverClientState == "cancelled") ->
            Text(appText(R.string.cloud_device_assist_cancelled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        report.completionAcked ||
            (report.serverState == "completed" && report.completionSource == "device") ->
            Text(appText(R.string.cloud_device_assist_completed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        report.runningAcked -> {
            if (expectedText != null) Text(
                appText(R.string.cloud_device_assist_scheduled, expectedText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(appText(if (expectedText == null)
                R.string.cloud_device_assist_awaiting_unknown
            else R.string.cloud_device_assist_awaiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Unit
    }
    val sourceLabel = when (report.serverSource) {
        "device_report" -> appText(R.string.cloud_source_device)
        "platform" -> appText(R.string.cloud_source_platform)
        else -> null
    }
    if (sourceLabel != null)
        Text(appText(R.string.cloud_source_label, sourceLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (report.estimatedAccepted)
        Text(appText(R.string.cloud_device_assist_accepted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (report.estimatedDeliveryState == "unknown")
        Text(appText(R.string.cloud_delivery_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
}

@Composable
private fun CloudIssueNotice(issue: CloudIssue) {
    val seconds = issue.detail?.toIntOrNull()?.takeIf { it > 0 }
    val text = when (issue.kind) {
        CloudIssueKind.NETWORK -> appText(R.string.cloud_error_network)
        CloudIssueKind.AUTH_REQUIRED -> appText(R.string.cloud_error_auth)
        CloudIssueKind.PAIRING_REJECTED -> appText(R.string.cloud_error_pairing)
        CloudIssueKind.INVALID_URL -> appText(R.string.cloud_error_url)
        CloudIssueKind.LOGIN_REQUIRED -> appText(R.string.cloud_error_login)
        CloudIssueKind.CONSENT_REQUIRED -> appText(R.string.cloud_error_consent)
        CloudIssueKind.SERVER_REJECTED -> appText(R.string.cloud_error_server)
        CloudIssueKind.RATE_LIMITED -> if (seconds != null)
            appText(R.string.cloud_error_rate_seconds, seconds)
        else appText(R.string.cloud_error_rate)
        CloudIssueKind.INVALID_RESPONSE -> appText(R.string.cloud_error_invalid_response)
        CloudIssueKind.UNBIND_FAILED -> appText(R.string.cloud_error_unbind)
        CloudIssueKind.NOT_CONFIGURED -> appText(R.string.cloud_error_notification)
        CloudIssueKind.NOT_BOUND -> appText(R.string.cloud_error_binding)
        CloudIssueKind.NOTIFICATION_FAILED -> appText(R.string.cloud_test_failed)
        CloudIssueKind.SYNC_PENDING -> upstreamText(issue.reasonCode) ?: issue.detail?.let {
            appText(R.string.cloud_sync_pending_http, it)
        } ?: appText(R.string.cloud_error_sync)
        CloudIssueKind.SUBSCRIPTION_REJECTED -> upstreamText(issue.reasonCode) ?: issue.detail?.let {
            appText(R.string.cloud_sync_rejected_http, it)
        } ?: appText(R.string.cloud_sync_rejected)
        CloudIssueKind.DEVICE_REPORT_REJECTED -> upstreamText(issue.reasonCode)
            ?: issue.detail?.let { appText(R.string.cloud_sync_rejected_http, it) }
            ?: appText(R.string.cloud_device_rejected)
        CloudIssueKind.VERSION_UNSUPPORTED -> appText(R.string.cloud_device_unsupported)
        CloudIssueKind.GENERIC -> appText(R.string.cloud_error_generic)
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f).controlContainer(),
        shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/**
 * Only the allowlisted upstream category is rendered. Unknown values return null so the caller
 * falls back to the self-hosted HTTP status; a raw server message is never shown.
 */
private fun upstreamText(reasonCode: String?): String? = when (reasonCode) {
    "upstream_http_405" -> appText(R.string.cloud_upstream_http_405)
    "upstream_http_403" -> appText(R.string.cloud_upstream_http_403)
    "upstream_unavailable", "upstream_http_429", "upstream_http_5xx" ->
        appText(R.string.cloud_upstream_retryable)
    "upstream_auth_required" -> appText(R.string.cloud_error_auth)
    "identity_mismatch" -> appText(R.string.cloud_upstream_identity_mismatch)
    "upstream_response_too_large", "upstream_invalid_response", "upstream_http_error",
    "upstream_rejected" -> appText(R.string.cloud_upstream_terminal)
    else -> null
}

@Composable
private fun TestResultNotice(status: CloudTestStatus) {
    val text = when (status) {
        CloudTestStatus.ACCEPTED -> appText(R.string.cloud_test_accepted)
        CloudTestStatus.UNKNOWN -> appText(R.string.cloud_test_unknown)
        CloudTestStatus.FAILED -> appText(R.string.cloud_test_failed)
    }
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (status == CloudTestStatus.ACCEPTED) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error)
}

@Composable
private fun statusText(status: CloudReminderStatus): String = when (status) {
    CloudReminderStatus.DISABLED -> appText(R.string.cloud_status_disabled)
    CloudReminderStatus.BOUND -> appText(R.string.cloud_status_bound)
    CloudReminderStatus.PENDING -> appText(R.string.cloud_status_pending)
    CloudReminderStatus.ERROR -> appText(R.string.cloud_status_error)
    CloudReminderStatus.AUTH_REQUIRED -> appText(R.string.cloud_status_auth_required)
}
