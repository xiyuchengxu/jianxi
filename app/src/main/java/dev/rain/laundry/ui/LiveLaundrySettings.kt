package dev.rain.laundry.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import dev.rain.laundry.ui.ControlIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rain.laundry.R
import dev.rain.laundry.core.LiveLaundryPhase
import dev.rain.laundry.i18n.LocalizedText as Text
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.notifications.LaundryLiveMonitor
import dev.rain.laundry.notifications.LiveLaundryNotification
import dev.rain.laundry.notifications.LiveLaundryPreferences
import dev.rain.laundry.notifications.LiveLaundryStatus
import dev.rain.laundry.notifications.LiveLaundryStatusStore
import java.util.Date

/**
 * Settings entry for the native live countdown. The preference defaults to on, but the
 * foreground service only starts when the current account has a platform-confirmed running
 * ordinary laundry order and the user is in a visible Activity.
 */
@Composable
internal fun LiveLaundrySettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember(context) { mutableStateOf(LiveLaundryPreferences.isEnabled(context)) }
    var notifications by remember(context) {
        mutableStateOf(LiveLaundryNotification.notificationsEnabled(context))
    }
    var status by remember(context) { mutableStateOf(LiveLaundryStatusStore.read(context)) }
    var hidden by remember(context) { mutableStateOf(LaundryLiveMonitor.isCurrentOrderDismissed(context)) }

    fun refresh() {
        enabled = LiveLaundryPreferences.isEnabled(context)
        notifications = LiveLaundryNotification.notificationsEnabled(context)
        status = LiveLaundryStatusStore.read(context)
        hidden = LaundryLiveMonitor.isCurrentOrderDismissed(context)
    }

    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        val watched = listOf(LiveLaundryPreferences.FILE, LiveLaundryStatusStore.FILE, "laundry_vault")
            .map { context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        watched.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            watched.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    AdjustableSurface("notifications.live", color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(appText(R.string.live_laundry_setting_title),
                        style = MaterialTheme.typography.titleSmall)
                    Text(appText(R.string.live_laundry_setting_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppSwitch(checked = enabled, onCheckedChange = {
                    enabled = it
                    LaundryLiveMonitor.onPreferenceChanged(context, it)
                    refresh()
                })
            }
            if (enabled) {
                Text(if (hidden) appText(R.string.live_laundry_setting_status_hidden) else statusText(context, status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hidden) AppTextButton(onClick = {
                    LaundryLiveMonitor.onPreferenceChanged(context, true)
                    refresh()
                }) { Text(appText(R.string.live_laundry_resume_current)) }
                if (!notifications) Text(appText(R.string.live_laundry_setting_notifications_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                Text(appText(R.string.live_laundry_setting_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(appText(R.string.live_laundry_setting_status_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppTextButton(onClick = {
                val promotion = Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, LiveLaundryNotification.CHANNEL_ID)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 36) context.startActivity(promotion)
                    else context.startActivity(channel)
                }.recoverCatching { context.startActivity(channel) }.recoverCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
            }) { Text(appText(R.string.live_laundry_manage_system)) }
        }
    }
}

private fun statusText(context: android.content.Context, status: LiveLaundryStatus?): String {
    if (status == null) return appText(R.string.live_laundry_setting_status_idle)
    // A killed service can leave the last local snapshot behind. Do not present it as current.
    val age = System.currentTimeMillis() - status.updatedAtMs
    if (age < 0 || age > 10 * 60_000L)
        return appText(R.string.live_laundry_setting_status_idle)
    return when (status.phase) {
        LiveLaundryPhase.OFFLINE -> appText(R.string.live_laundry_setting_status_offline)
        LiveLaundryPhase.ESTIMATE_EXPIRED -> appText(R.string.live_laundry_setting_status_expired)
        LiveLaundryPhase.TIME_UNKNOWN -> appText(R.string.live_laundry_setting_status_unknown)
        LiveLaundryPhase.FINISHED -> appText(R.string.live_laundry_setting_status_finished)
        LiveLaundryPhase.RUNNING -> status.expectedEndAtMs?.let {
            appText(R.string.live_laundry_setting_status_running,
                DateFormat.getTimeFormat(context).format(Date(it)))
        } ?: appText(R.string.live_laundry_setting_status_idle)
        LiveLaundryPhase.STOPPED -> appText(R.string.live_laundry_setting_status_idle)
    }
}
