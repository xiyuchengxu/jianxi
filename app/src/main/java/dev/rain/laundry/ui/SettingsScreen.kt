package dev.rain.laundry.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit, openTheme: () -> Unit, enabled: Boolean) {
    Card(shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = openTheme)
                .semantics { contentDescription = "主题设置" }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("主题", style = MaterialTheme.typography.titleMedium)
                    Text("外观、配色与底栏样式", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch("洗衣", Icons.Outlined.LocalLaundryService, settings.laundryEnabled, enabled) {
                onChange(settings.copy(laundryEnabled = it))
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ServiceSwitch("饮水", Icons.Outlined.WaterDrop, settings.waterEnabled, enabled) {
                onChange(settings.copy(waterEnabled = it))
            }
        }
    }
    Text("更改即时生效，并自动保存。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ServiceSwitch(title: String, icon: ImageVector, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = "${title}功能开关" }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("在底栏显示${title}入口", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
