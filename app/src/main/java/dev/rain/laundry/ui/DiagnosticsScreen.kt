package dev.rain.laundry.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rain.laundry.R
import dev.rain.laundry.diagnostics.DiagnosticOperation
import dev.rain.laundry.diagnostics.DiagnosticStatus
import dev.rain.laundry.diagnostics.DiagnosticsLog
import dev.rain.laundry.diagnostics.DiagnosticsExportResult
import dev.rain.laundry.diagnostics.DiagnosticsExportViewModel
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.appText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Content for LaundryApp's existing scroll container; this component adds no nested scrolling. */
@Composable
internal fun DiagnosticsScreen() {
    val context = LocalContext.current
    LaunchedEffect(context.applicationContext) { DiagnosticsLog.initialize(context) }
    val entries by DiagnosticsLog.entries.collectAsStateWithLifecycle()
    val storageError by DiagnosticsLog.storageError.collectAsStateWithLifecycle()
    val exportModel: DiagnosticsExportViewModel = viewModel()
    val exportState by exportModel.state.collectAsStateWithLifecycle()
    // Only the outstanding picker request is saved. The ViewModel owns any returned URI/write job.
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val exporting = pendingExport != null || exportState.writing
    val exportMessage = when (exportState.result) {
        DiagnosticsExportResult.SAVED -> R.string.diagnostics_export_done
        DiagnosticsExportResult.FAILED -> R.string.diagnostics_export_failed
        null -> 0
    }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = pendingExport
        pendingExport = null
        if (uri != null && snapshot != null) exportModel.export(uri, snapshot)
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(appText(R.string.diagnostics_summary, DiagnosticsLog.MAX_ENTRIES),
            modifier = Modifier.wallpaperTextBacking(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppButton(onClick = {
                pendingExport = DiagnosticsLog.exportText()
                exportModel.clearResult()
                try {
                    launcher.launch("jianxi-diagnostics.txt")
                } catch (_: Exception) {
                    pendingExport = null
                    exportModel.pickerFailed()
                }
            }, enabled = !exporting, modifier = Modifier.weight(1f).testTag("diagnosticsExport")) {
                Text(appText(R.string.diagnostics_export))
            }
            AppOutlinedButton(onClick = { clearConfirmation = true }, enabled = entries.isNotEmpty() && !exporting,
                modifier = Modifier.weight(1f).testTag("diagnosticsClear")) {
                Text(appText(R.string.diagnostics_clear))
            }
        }
        if (exporting) Text(appText(R.string.diagnostics_exporting), modifier = Modifier.wallpaperTextBacking(), style = MaterialTheme.typography.bodySmall)
        if (exportMessage != 0) Text(appText(exportMessage), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.wallpaperTextBacking(), color = if (exportMessage == R.string.diagnostics_export_failed) colors.error else colors.onSurfaceVariant)
        if (storageError) Text(appText(R.string.diagnostics_storage_failed),
            modifier = Modifier.wallpaperTextBacking(), style = MaterialTheme.typography.bodyMedium, color = colors.error)
        AppCard(shape = appShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(appText(R.string.diagnostics_count, entries.size), style = MaterialTheme.typography.titleMedium)
                if (entries.isEmpty()) {
                    Text(appText(R.string.diagnostics_empty), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant, modifier = Modifier.testTag("diagnosticsEmpty"))
                } else {
                    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", AppStrings.locale())
                        .withZone(ZoneId.systemDefault())
                    entries.asReversed().forEachIndexed { index, value ->
                        if (index > 0) HorizontalDivider()
                        Column(Modifier.controlGlyphContrast(colors.onSurface), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(format.format(Instant.ofEpochMilli(value.timeMs)),
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(appText(value.operation.label), style = MaterialTheme.typography.bodyLarge)
                            Text(appText(value.status.label), style = MaterialTheme.typography.bodyMedium,
                                color = if (value.status == DiagnosticStatus.SUCCESS) colors.primary else colors.onSurfaceVariant)
                            value.durationMs?.let {
                                Text(appText(R.string.diagnostics_duration, it),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            value.errorCode?.let {
                                Text(appText(R.string.diagnostics_error_code, it),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (clearConfirmation) AlertDialog(
        onDismissRequest = { clearConfirmation = false },
        title = { Text(appText(R.string.diagnostics_clear_confirm_title)) },
        text = { Text(appText(R.string.diagnostics_clear_confirm_body)) },
        confirmButton = {
            AppTextButton(onClick = {
                DiagnosticsLog.clear()
                exportModel.clearResult()
                clearConfirmation = false
            }, modifier = Modifier.testTag("diagnosticsClearConfirm")) {
                Text(appText(R.string.diagnostics_clear_confirm))
            }
        },
        dismissButton = {
            AppTextButton(onClick = { clearConfirmation = false },
                modifier = Modifier.testTag("diagnosticsClearCancel")) {
                Text(appText(R.string.diagnostics_clear_cancel))
            }
        }
    )
}

private val DiagnosticOperation.label: Int get() = when (this) {
    DiagnosticOperation.APP_FOREGROUND -> R.string.diagnostics_op_app_foreground
    DiagnosticOperation.SMS -> R.string.diagnostics_op_sms
    DiagnosticOperation.LOGIN -> R.string.diagnostics_op_login
    DiagnosticOperation.DEVICE_LOOKUP -> R.string.diagnostics_op_device_lookup
    DiagnosticOperation.DEVICE_STATUS -> R.string.diagnostics_op_device_status
    DiagnosticOperation.MODE_LIST -> R.string.diagnostics_op_mode_list
    DiagnosticOperation.QUOTE -> R.string.diagnostics_op_quote
    DiagnosticOperation.ORDER_CREATE -> R.string.diagnostics_op_order_create
    DiagnosticOperation.ORDER_QUERY -> R.string.diagnostics_op_order_query
    DiagnosticOperation.ORDER_SYNC -> R.string.diagnostics_op_order_sync
    DiagnosticOperation.ORDER_CANCEL -> R.string.diagnostics_op_order_cancel
    DiagnosticOperation.PAYMENT_CHECK -> R.string.diagnostics_op_payment_check
    DiagnosticOperation.PAYMENT_SDK_RETURN -> R.string.diagnostics_op_payment_sdk_return
    DiagnosticOperation.PAYMENT_ROUTE -> R.string.diagnostics_op_payment_route
    DiagnosticOperation.PAYMENT_PREPARE -> R.string.diagnostics_op_payment_prepare
    DiagnosticOperation.PAYMENT_SUBMIT -> R.string.diagnostics_op_payment_submit
    DiagnosticOperation.PAYMENT_QUERY -> R.string.diagnostics_op_payment_query
    DiagnosticOperation.WATER_ROUTE -> R.string.diagnostics_op_water_route
    DiagnosticOperation.WATER_ROUTE_SELECT -> R.string.diagnostics_op_water_route_select
    DiagnosticOperation.WATER_BILL_ROUTE -> R.string.diagnostics_op_water_bill_route
    DiagnosticOperation.WATER_AUTH_POLICY -> R.string.diagnostics_op_water_auth_policy
    DiagnosticOperation.WATER_START -> R.string.diagnostics_op_water_start
    DiagnosticOperation.WATER_QUERY -> R.string.diagnostics_op_water_query
    DiagnosticOperation.WATER_STOP -> R.string.diagnostics_op_water_stop
    DiagnosticOperation.WATER_COMMAND_QUERY -> R.string.diagnostics_op_water_command_query
    DiagnosticOperation.WATER_SETTLEMENT -> R.string.diagnostics_op_water_settlement
    DiagnosticOperation.AUTHORIZATION_QUERY -> R.string.diagnostics_op_authorization_query
    DiagnosticOperation.AUTHORIZATION_OPEN -> R.string.diagnostics_op_authorization_open
}

private val DiagnosticStatus.label: Int get() = when (this) {
    DiagnosticStatus.SUCCESS -> R.string.diagnostics_status_success
    DiagnosticStatus.REJECTED -> R.string.diagnostics_status_rejected
    DiagnosticStatus.NETWORK_ERROR -> R.string.diagnostics_status_network_error
    DiagnosticStatus.SESSION_EXPIRED -> R.string.diagnostics_status_session_expired
    DiagnosticStatus.INVALID_RESPONSE -> R.string.diagnostics_status_invalid_response
    DiagnosticStatus.CANCELLED -> R.string.diagnostics_status_cancelled
    DiagnosticStatus.HTTP_ERROR -> R.string.diagnostics_status_http_error
    DiagnosticStatus.FAILED -> R.string.diagnostics_status_failed
}
