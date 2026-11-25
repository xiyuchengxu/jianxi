package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import dev.rain.laundry.ui.ControlIcon as Icon
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rain.laundry.notifications.LaundryLiveMonitor
import dev.rain.laundry.notifications.LaundryReminders
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.cloud.CloudReminderUiState

@Composable
internal fun NotificationSettingsScreen(syncError: String?, cloud: CloudReminderUiState,
    loggedIn: Boolean,
    onBind: (String, String, String, Boolean) -> Unit,
    onCloudRefresh: () -> Unit,
    onCloudRetry: () -> Unit,
    onCloudUnbind: () -> Unit,
    onCloudReauthorize: () -> Unit,
    onCloudUpdateCode: (String) -> Unit,
    onCloudTest: () -> Unit,
    onCloudEnableDeviceAssisted: () -> Unit = {}
) {
    Text(appText(R.string.msg_1ad91bc551f7 , OrderHistory.LIMIT),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LaundryReminderPermission()
    LiveLaundrySettings()
    ShizukuKeepAliveSettings()
    Text(syncError ?: appText(R.string.msg_7eb9be087d93),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LaunchedEffect(loggedIn) {
        if (loggedIn) onCloudRefresh()
    }
    CloudReminderSettings(cloud, loggedIn, onBind, onCloudRefresh, onCloudRetry,
        onCloudUnbind, onCloudReauthorize, onCloudUpdateCode, onCloudTest,
        onEnableDeviceAssisted = onCloudEnableDeviceAssisted)
}

@Composable
internal fun LaundryReminderPermission() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(LaundryReminders.notificationsEnabled(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = LaundryReminders.notificationsEnabled(context)
        if (enabled) {
            LaundryReminders.publishPending(context)
            LaundryLiveMonitor.onAppForeground(context)
        }
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = LaundryReminders.notificationsEnabled(context)
                if (enabled) LaundryReminders.publishPending(context)
                // The notification settings page is a visible Activity; this is a safe place
                // to resume a live countdown after the system killed the service.
                LaundryLiveMonitor.onAppForeground(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AdjustableSurface("notifications.permission", color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (enabled) appText(R.string.msg_2dbf7aacc433) else appText(R.string.msg_63ccc6721672),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!enabled) AppTextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !requested) {
                        requested = true
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                    }
                }) { Text(appText(R.string.msg_3834f9cf9f9b)) }
            }
            Text(appText(R.string.msg_13c5cfb46dc9),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
