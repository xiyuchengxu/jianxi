package dev.rain.laundry.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
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
            Text(if (device == null) "开始饮水" else device.name, Modifier.testTag("waterDeviceName"),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("多喝热水～", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (device == null) {
        Text("扫描饮水机上的二维码，\n选择出水口后开始用水。", style = MaterialTheme.typography.bodyLarge)
        if (state.waterSession?.active == true) {
            Text("有一条饮水记录待处理，请扫描原设备或在订单中恢复。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = orders, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("查看进行中的饮水记录")
            }
        }
    } else {
        val title = when {
            session?.phase == WaterPhase.SETTLING -> "用水已结束，正在核对账单"
            session?.phase == WaterPhase.ENDED -> "本次用水已结束"
            device.error.isNotBlank() -> "设备状态异常"
            active && state.waterStatus?.status == 6 -> "本次用水状态异常，请刷新核对"
            session?.phase == WaterPhase.ENDING -> "正在等待设备结束用水"
            session?.phase == WaterPhase.USING -> "正在用水"
            session?.phase in setOf(WaterPhase.STARTING, WaterPhase.WAITING) -> "等待饮水机响应"
            active -> "上次用水结果待确认"
            state.waterStatus?.workStatus == 3 -> "设备正在被他人使用"
            state.waterStatus?.mine == true -> "当前账户正在用水，请刷新恢复会话"
            state.waterStatus?.available == true -> if (state.waterStatus.status == 6) "出水口空闲，请重新确认用水"
                else "请选择面前饮水机的出水口"
            else -> "请刷新并确认设备状态"
        }
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text("支付方式：${payment?.title ?: "待核对"} · 按实际用量结算", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (active || !session?.amount.isNullOrBlank()) {
                    Text(session?.amount?.takeIf { it.isNotBlank() }?.let { "¥$it" } ?: "金额待同步",
                        style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if (session?.phase == WaterPhase.ENDED) "平台返回的结算金额" else "实际金额以平台结算账单为准",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (active && session?.phase !in setOf(WaterPhase.SETTLING, WaterPhase.ENDED)) {
                    Text("请在机器上操作出水，离开前确认已停止。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (device.announcement.isNotBlank()) Text(device.announcement.take(600), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (device.error.isNotBlank()) Text(device.error, color = MaterialTheme.colorScheme.error)
        Text("出水口", style = MaterialTheme.typography.titleMedium)
        device.outlets.forEach { candidate ->
            val selected = outlet?.id == candidate.id
            OutlinedCard(onClick = { select(candidate) }, enabled = !state.busy && !active && !authorizationPending,
                shape = RoundedCornerShape(22.dp), border = BorderStroke(if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.name, style = MaterialTheme.typography.titleMedium)
                        Text(candidate.rate.ifBlank { "计费标准请核对机身标价及平台账单" }, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (candidate.feature.isNotBlank()) Text(candidate.feature.take(160), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!active && state.waterPayments.isNotEmpty()) {
            Text("支付方式", style = MaterialTheme.typography.titleMedium)
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
                Text(if (authorizationPending) "等待支付宝授权核验" else "开始用水")
            }
        } else if (device.supportAppFinish && state.waterStatus?.mine == true) {
            Button(onClick = { endDialog = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.StopCircle, null); Spacer(Modifier.width(8.dp)); Text("结束用水")
            }
        }
        if (active && !device.supportAppFinish) Text("这台设备请使用机身停止按钮结束出水，再刷新状态。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (active && session?.phase == WaterPhase.UNKNOWN) Text("请先检查设备、刷新状态。结果未确认时不会再次发送启动指令。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = refresh, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("刷新饮水状态")
        }
        if (session?.settlementOrder != null) OutlinedButton(onClick = orders, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("查看饮水账单") }
    }
    if (!active) {
        if (device == null) Button(onClick = scan, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("扫描饮水机")
        } else OutlinedButton(onClick = scan, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("重新扫描饮水机")
        }
        OutlinedButton(onClick = paste, enabled = !state.busy && !authorizationPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("粘贴饮水机设备码")
        }
    }
    if (startDialog && device != null && outlet != null && payment != null) AlertDialog(onDismissRequest = { startDialog = false },
        title = { Text("确认开始用水？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${device.name} · ${outlet.name}")
            Text(outlet.rate.ifBlank { "请先核对机器现场的计费标准。" })
            Text("本次使用${payment.title}，按实际用量结算。请准备好容器，结束后确认机器已经停止出水。")
            if (payment.requiresAlipayAgreement) {
                Text("本次使用支付宝先享后付（免密），按实际用量由平台结算扣款。确认后会核对本设备的签约要求；需要签约时将打开支付宝，返回后须再次确认用水。未付账单请到订单中核对金额和付款状态。")
            } else if (payment.method == PayMethod.ALIPAY) {
                Text("平台可能根据已有支付宝授权自动扣款；未付账单可在订单中继续支付。")
            }
        } }, confirmButton = { TextButton(onClick = { startDialog = false; start() },
            enabled = !state.busy && state.waterPaymentAllowed) { Text("确认开始用水") } },
        dismissButton = { TextButton(onClick = { startDialog = false }) { Text("取消") } })
    if (endDialog) AlertDialog(onDismissRequest = { endDialog = false }, title = { Text("结束本次用水？") },
        text = { Text("将请求饮水机停止本次用水，并查询平台结算账单。请同时确认机器已停止出水。") },
        confirmButton = { TextButton(onClick = { endDialog = false; finish() }, enabled = !state.busy) { Text("确认结束用水") } },
        dismissButton = { TextButton(onClick = { endDialog = false }) { Text("继续用水") } })
}
