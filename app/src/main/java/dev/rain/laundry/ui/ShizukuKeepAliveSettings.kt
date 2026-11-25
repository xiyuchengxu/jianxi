package dev.rain.laundry.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rain.laundry.R
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.keepalive.KeepAliveIssue
import dev.rain.laundry.keepalive.ShizukuKeepAliveManager

/** This card is independent of account, order and notification permissions. Opting out stays usable. */
@Composable
internal fun ShizukuKeepAliveSettings() {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { ShizukuKeepAliveManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) manager.refresh()
        }
        lifecycle.addObserver(observer)
        manager.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    AppCard(controlId = "notifications.keepalive", colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText(R.string.shizuku_title), style = MaterialTheme.typography.titleMedium)
                    Text(appText(R.string.shizuku_optional), style = MaterialTheme.typography.bodySmall)
                }
                AppSwitch(checked = state.preference.enabled, onCheckedChange = manager::setEnabled,
                    enabled = !state.busy)
            }
            val status = when {
                !state.preference.enabled && state.preference.introduced -> R.string.shizuku_pending_restore
                state.preference.enabled && state.guardRunning -> R.string.shizuku_enabled
                state.preference.enabled -> R.string.shizuku_reapply_needed
                state.batteryExempt -> R.string.shizuku_existing
                else -> R.string.shizuku_disabled
            }
            Text(appText(status), style = MaterialTheme.typography.bodyMedium)
            Text(appText(when {
                !state.running -> R.string.shizuku_unavailable
                !state.permissionGranted -> R.string.shizuku_not_authorized
                else -> R.string.shizuku_ready
            }), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.issue?.let { issue ->
                Text(appText(when (issue) {
                    KeepAliveIssue.UNAVAILABLE -> R.string.shizuku_unavailable
                    KeepAliveIssue.OLD_VERSION -> R.string.shizuku_old_version
                    KeepAliveIssue.DENIED -> R.string.shizuku_denied
                    KeepAliveIssue.FAILED -> R.string.shizuku_failure
                    KeepAliveIssue.STORAGE -> R.string.shizuku_storage
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!state.preference.enabled && state.preference.introduced) {
                AppTextButton(onClick = { manager.setEnabled(false) }, enabled = !state.busy) {
                    Text(appText(R.string.shizuku_retry_restore))
                }
            } else if (state.preference.enabled && (!state.guardRunning || !state.batteryExempt)) {
                AppTextButton(onClick = { manager.setEnabled(true) }, enabled = !state.busy) {
                    Text(appText(R.string.shizuku_reapply))
                }
            }
            AppTextButton(onClick = {
                val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                runCatching { context.startActivity(launch ?: Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }
            }, enabled = !state.busy) { Text(appText(R.string.shizuku_open)) }
            AppTextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .recoverCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))) }
            }, enabled = !state.busy) { Text(appText(R.string.shizuku_battery_settings)) }
        }
    }
}
