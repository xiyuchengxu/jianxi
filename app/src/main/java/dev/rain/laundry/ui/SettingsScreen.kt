package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import dev.rain.laundry.ui.ControlIcon as Icon
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit, openTheme: () -> Unit,
    enabled: Boolean, openNotifications: () -> Unit = {}, openDiagnostics: () -> Unit = {},
    onLogout: (() -> Unit)? = null) {
    val latest = remember { mutableStateOf(settings) }
    SideEffect { latest.value = settings }
    fun edit(transform: (ThemeSettings) -> ThemeSettings) {
        val updated = transform(latest.value)
        latest.value = updated
        onChange(updated)
    }
    AppCard(controlId = "settings.card", shape = appShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth()) {
            LanguageSettings(enabled)
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            AdjustableSettingRow("settings.theme", Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = openTheme)
                .semantics { contentDescription = appText(R.string.msg_ab98197175b0) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText(R.string.msg_788db1cfec2a), style = MaterialTheme.typography.titleMedium)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            AdjustableSettingRow("settings.notifications", Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = openNotifications)
                .semantics { contentDescription = appText(R.string.msg_247ca01ae280) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText(R.string.msg_4b6ded8a7f18), style = MaterialTheme.typography.titleMedium)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            AdjustableSettingRow("settings.diagnostics", Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = openDiagnostics)
                .semantics { contentDescription = appText(R.string.diagnostics_title) }.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(appText(R.string.diagnostics_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch("settings.laundry", appText(R.string.msg_bc982c81c510), Icons.Outlined.LocalLaundryService, settings.laundryEnabled, enabled) {
                val checked = it
                edit { value -> value.copy(laundryEnabled = checked) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch("settings.dryer", appText(R.string.saved_device_kind_dryer), Icons.Outlined.LocalLaundryService, settings.dryerEnabled, enabled) {
                val checked = it
                edit { value -> value.copy(dryerEnabled = checked) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch("settings.water", appText(R.string.msg_6edae041a323), Icons.Outlined.WaterDrop, settings.waterEnabled, enabled) {
                val checked = it
                edit { value -> value.copy(waterEnabled = checked) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch("settings.scanner", appText(R.string.settings_scanner_title), Icons.Outlined.QrCodeScanner,
                settings.scannerEnabled, enabled) {
                val checked = it
                edit { value -> value.copy(scannerEnabled = checked) }
            }
        }
    }
    Text(appText(R.string.msg_7f48fe35e1c4), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (onLogout != null) {
        var confirmLogout by rememberSaveable { mutableStateOf(false) }
        AppOutlinedButton(controlId = "settings.logout", onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
            Text(appText(R.string.settings_log_out))
        }
        if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
            title = { Text(appText(R.string.msg_b6af1a5049fc)) },
            text = { Text(appText(R.string.msg_cdc013bc1922)) },
            confirmButton = { AppTextButton(onClick = { confirmLogout = false; onLogout() }, enabled = enabled) {
                Text(appText(R.string.settings_log_out))
            } },
            dismissButton = { AppTextButton(onClick = { confirmLogout = false }) {
                Text(appText(R.string.msg_2cd0f3be8738))
            } })
    }
}

@Composable
private fun ServiceSwitch(controlId: String, title: String, icon: ImageVector, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit) {
    AdjustableSettingRow("$controlId.row", Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = appText(R.string.msg_9d8fb9c546b1 , title) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        AppSwitch(checked = checked, onCheckedChange = null, enabled = enabled, controlId = controlId)
    }
}
