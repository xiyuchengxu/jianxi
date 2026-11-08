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
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit, openTheme: () -> Unit,
    enabled: Boolean, openNotifications: () -> Unit = {}) {
    Card(shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth()) {
            LanguageSettings(enabled)
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = openTheme)
                .semantics { contentDescription = appText(R.string.msg_ab98197175b0) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText(R.string.msg_788db1cfec2a), style = MaterialTheme.typography.titleMedium)
                    Text(appText(R.string.msg_79080acb89b3), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = openNotifications)
                .semantics { contentDescription = appText(R.string.msg_247ca01ae280) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText(R.string.msg_4b6ded8a7f18), style = MaterialTheme.typography.titleMedium)
                    Text(appText(R.string.msg_ecf99c00e14f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch(appText(R.string.msg_bc982c81c510), Icons.Outlined.LocalLaundryService, settings.laundryEnabled, enabled) {
                onChange(settings.copy(laundryEnabled = it))
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch(appText(R.string.msg_6edae041a323), Icons.Outlined.WaterDrop, settings.waterEnabled, enabled) {
                onChange(settings.copy(waterEnabled = it))
            }
        }
    }
    Text(appText(R.string.msg_7f48fe35e1c4), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ServiceSwitch(title: String, icon: ImageVector, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = appText(R.string.msg_9d8fb9c546b1 , title) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(appText(R.string.msg_5f63b1959faf , title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
