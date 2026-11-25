package dev.rain.laundry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rain.laundry.R
import dev.rain.laundry.core.PointsTask
import dev.rain.laundry.core.PointsUiState
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage

/** Settings-page content; task names and descriptions returned by the platform stay verbatim. */
@Composable
internal fun PointsScreen(
    state: PointsUiState,
    loggedIn: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onOpenOriginal: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val working = state.loading || state.running
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(shape = appShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Stars, null, tint = colors.onPrimaryContainer)
                    Text(appText(R.string.points_balance), style = MaterialTheme.typography.titleMedium,
                        color = colors.onPrimaryContainer)
                }
                Text(state.balance ?: "—", style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold, color = colors.onPrimaryContainer,
                    modifier = Modifier.testTag("pointsBalance"))
                Text(appText(when (state.signedInToday) {
                    true -> R.string.points_already_signed
                    false -> R.string.points_unsigned_today
                    null -> R.string.points_sign_status_unknown
                }), style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
            }
        }
        if (!loggedIn) {
            Text(appText(R.string.points_login_required), color = colors.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, enabled = enabled && !working,
                    modifier = Modifier.fillMaxWidth().testTag("pointsStart")) {
                    Text(appText(R.string.points_start))
                }
                OutlinedButton(onClick = onRefresh, enabled = enabled && !working,
                    modifier = Modifier.fillMaxWidth().testTag("pointsRefresh")) {
                    Text(appText(R.string.points_refresh))
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.progress.isNotBlank()) {
                Text(localizedMessage(state.progress), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, modifier = Modifier.testTag("pointsProgress"))
            }
            state.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(localizedMessage(error), color = colors.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pointsError"))
            }
            Card(shape = appShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(appText(R.string.points_tasks_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (state.tasks.isEmpty()) {
                        Text(appText(if (state.loaded) R.string.points_empty else R.string.points_not_loaded),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    state.tasks.forEachIndexed { index, task ->
                        if (index > 0) HorizontalDivider()
                        PointsTaskRow(task)
                    }
                }
            }
            OutlinedButton(onClick = onOpenOriginal, enabled = enabled && !working,
                modifier = Modifier.fillMaxWidth().testTag("pointsOpenOriginal")) {
                Text(appText(R.string.points_open_original))
            }
            if (state.logs.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText(R.string.points_logs_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    state.logs.forEach { message ->
                        Text(localizedMessage(message), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PointsTaskRow(task: PointsTask) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("pointsTask:${task.id}"),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (task.done) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = colors.primary)
            }
        }
        task.reward?.takeIf { it.isNotBlank() }?.let { reward ->
            Text(appText(R.string.points_task_reward, reward),
                style = MaterialTheme.typography.bodyMedium, color = colors.primary)
        }
        task.completed?.let { completed ->
            Text(if (task.limit != null) appText(R.string.points_task_progress, completed, task.limit)
                else appText(R.string.points_task_completed_count, completed),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (task.description.isNotBlank()) {
            Text(task.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(appText(if (task.done) R.string.points_task_done else R.string.points_task_original),
            style = MaterialTheme.typography.bodySmall,
            color = if (task.done) colors.primary else colors.onSurfaceVariant)
    }
}
