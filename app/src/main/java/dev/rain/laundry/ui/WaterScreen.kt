package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rain.laundry.UiState
import dev.rain.laundry.core.*

@Composable
internal fun WaterScreen(
    state: UiState, scan: () -> Unit, paste: () -> Unit, select: (WaterOutlet) -> Unit,
    start: () -> Unit, finish: () -> Unit, refresh: () -> Unit, orders: () -> Unit,
    selectPayment: (WaterPayRoute) -> Unit = {}
) {
    val device = state.waterDevice
    val outlet = state.waterOutlet
    val session = state.waterSession?.takeIf { it.device.id == device?.id && it.outlet.id == outlet?.id }
    val active = session?.active == true
    val authorizationPending = state.waterAuthorizationPending
    val payment = if (active) session?.payment ?: if (session?.recovered == true) null else WaterPayRoute.BALANCE
        else state.waterPayment
    var startDialog by rememberSaveable { mutableStateOf(false) }
    var endDialog by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("waterHeader"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.WaterDrop, null, Modifier.padding(18.dp).size(32.dp).testTag("waterHeaderIcon"),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (device == null) appText(R.string.msg_88ef220dbc2f) else device.name, Modifier.testTag("waterDeviceName"),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, localize = false)
            Text(appText(R.string.msg_0e5d45e9456f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (device == null) {
        Text(appText(R.string.msg_8032591e13e5), style = MaterialTheme.typography.bodyLarge)
        if (state.waterSession?.active == true) {
            Text(appText(R.string.msg_edbdfa509c1e), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = orders, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(appText(R.string.msg_6b59ed9d733e))
            }
        }
    } else {
        val title = when {
            session?.phase == WaterPhase.SETTLING -> appText(R.string.msg_5f386787584a)
            session?.phase == WaterPhase.ENDED -> appText(R.string.msg_9ff5a9563ee2)
            device.error.isNotBlank() -> appText(R.string.msg_c8592490c32d)
            active && state.waterStatus?.status == 6 -> appText(R.string.msg_055ff46ac059)
            session?.phase == WaterPhase.ENDING -> appText(R.string.msg_15d2dda731b4)
            session?.phase == WaterPhase.USING -> appText(R.string.msg_5bdb9263878d)
            session?.phase in setOf(WaterPhase.STARTING, WaterPhase.WAITING) -> appText(R.string.msg_79cea7bbcb4f)
            active -> appText(R.string.msg_cfc54865db1f)
            state.waterStatus?.workStatus == 3 -> appText(R.string.msg_bbad1fd91c78)
            state.waterStatus?.mine == true -> appText(R.string.msg_06e43ba08011)
            state.waterStatus?.available == true -> if (state.waterStatus.status == 6) appText(R.string.msg_22739db68677)
                else appText(R.string.msg_07a3c2feadd0)
            else -> appText(R.string.msg_f088643d2a3b)
        }
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(appText(R.string.msg_845461671a4e , payment?.title?.let(::localizedMessage) ?: appText(R.string.msg_33df16b4afab)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (active || !session?.amount.isNullOrBlank()) {
                    Text(session?.amount?.takeIf { it.isNotBlank() }?.let { "¥$it" } ?: appText(R.string.msg_6c61dc3a5f3d),
                        style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if (session?.phase == WaterPhase.ENDED) appText(R.string.msg_6d1262e5d2e0) else appText(R.string.msg_4d84a10ccdee),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (active && session?.phase !in setOf(WaterPhase.SETTLING, WaterPhase.ENDED)) {
                    Text(appText(R.string.msg_0841fa3cbaff), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (device.announcement.isNotBlank()) Text(device.announcement.take(600), color = MaterialTheme.colorScheme.onSurfaceVariant, localize = false)
        if (device.error.isNotBlank()) Text(device.error, color = MaterialTheme.colorScheme.error)
        Text(appText(R.string.msg_717d0bf23fb3), style = MaterialTheme.typography.titleMedium)
        device.outlets.forEach { candidate ->
            val selected = outlet?.id == candidate.id
            OutlinedCard(onClick = { select(candidate) }, enabled = !state.busy && !active && !authorizationPending,
                shape = RoundedCornerShape(22.dp), border = BorderStroke(if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.name, style = MaterialTheme.typography.titleMedium, localize = false)
                        Text(candidate.rate.ifBlank { appText(R.string.msg_fe33d85393d6) }, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, localize = false)
                        if (candidate.feature.isNotBlank()) Text(candidate.feature.take(160), style = MaterialTheme.typography.bodySmall, localize = false)
                    }
                }
            }
        }
        if (!active && state.waterPayments.isNotEmpty()) {
            Text(appText(R.string.msg_ca7223717fa6), style = MaterialTheme.typography.titleMedium)
            state.waterPayments.forEach { candidate ->
                val selected = candidate == state.waterPayment
                OutlinedCard(onClick = { selectPayment(candidate) }, enabled = !state.busy && !authorizationPending,
                    shape = RoundedCornerShape(22.dp), border = BorderStroke(if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, null)
                        Spacer(Modifier.width(12.dp))
                        Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        state.waterPaymentError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        state.waterUpdateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        state.waterAuthorizationMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (!active) {
            Button(onClick = { startDialog = true },
                enabled = !state.busy && outlet != null && device.error.isBlank() && state.waterPaymentAllowed &&
                    state.waterStatus?.available == true,
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.WaterDrop, null); Spacer(Modifier.width(8.dp))
                Text(if (authorizationPending) appText(R.string.msg_d0f6b2e6e6e0) else appText(R.string.msg_be376c412d1f))
            }
        } else if (device.supportAppFinish && state.waterStatus?.mine == true) {
            Button(onClick = { endDialog = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.StopCircle, null); Spacer(Modifier.width(8.dp)); Text(appText(R.string.msg_9055c1d4ca6f))
            }
        }
        if (active && !device.supportAppFinish) Text(appText(R.string.msg_321554ba3c42),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (active && session?.phase == WaterPhase.UNKNOWN) Text(appText(R.string.msg_4167b9536d27),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = refresh, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text(appText(R.string.msg_083c5e6e61a7))
        }
        if (session?.settlementOrder != null) OutlinedButton(onClick = orders, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(appText(R.string.msg_f617edd7b975)) }
    }
    if (!active) {
        if (device == null) Button(onClick = scan, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(appText(R.string.msg_dd3246e28288))
        } else OutlinedButton(onClick = scan, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(appText(R.string.msg_4dca55259348))
        }
        OutlinedButton(onClick = paste, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text(appText(R.string.msg_fc984db26cf9))
        }
    }
    if (startDialog && device != null && outlet != null && payment != null) AlertDialog(onDismissRequest = { startDialog = false },
        title = { Text(appText(R.string.msg_09b6b52a8ffc)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${device.name} · ${outlet.name}", localize = false)
            Text(outlet.rate.ifBlank { appText(R.string.msg_a7afd35b9a01) }, localize = false)
            Text(appText(R.string.msg_914133ec9e6e , localizedMessage(payment.title)))
            if (payment.requiresAlipayAgreement) {
                Text(appText(R.string.msg_960aa8eb1c5d))
            } else if (payment.method == PayMethod.ALIPAY) {
                Text(appText(R.string.msg_43a112a16373))
            }
        } }, confirmButton = { TextButton(onClick = { startDialog = false; start() },
            enabled = !state.busy && state.waterPaymentAllowed) { Text(appText(R.string.msg_97c0474b816d)) } },
        dismissButton = { TextButton(onClick = { startDialog = false }) { Text(appText(R.string.msg_2cd0f3be8738)) } })
    if (endDialog) AlertDialog(onDismissRequest = { endDialog = false }, title = { Text(appText(R.string.msg_ca0dffccb99d)) },
        text = { Text(appText(R.string.msg_00612aad0812)) },
        confirmButton = { TextButton(onClick = { endDialog = false; finish() }, enabled = !state.busy) { Text(appText(R.string.msg_6ce4fd181551)) } },
        dismissButton = { TextButton(onClick = { endDialog = false }) { Text(appText(R.string.msg_9a81c4e994ca)) } })
}
