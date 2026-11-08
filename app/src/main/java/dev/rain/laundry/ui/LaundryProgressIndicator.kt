package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.rain.laundry.core.*
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun LaundryProgressIndicator(order: LocalOrder, now: Long) {
    if (order.isWater || order.phase !in setOf(OrderPhase.PAID, OrderPhase.COMPLETED)) return
    val progress = order.laundryProgress
    if (order.phase == OrderPhase.COMPLETED && progress == null) return
    val finished = progress?.completionConfirmed == true
    val running = progress?.runState == LaundryRunState.RUNNING
    val remaining = progress?.remainingMillis(now)
    val fraction = when {
        finished -> 1f
        running -> progress?.remainingFraction(now)?.let { (1f - it).coerceIn(0f, .99f) }
        else -> null
    }
    val status = when {
        finished -> appText(R.string.msg_409c4e104012)
        running && remaining != null && remaining > 0 -> {
            val seconds = (remaining + 999) / 1000
            appText(R.string.msg_a2d0549991f7 , seconds / 60, seconds % 60)
        }
        running && remaining == 0L -> appText(R.string.msg_5192dad3dee3)
        running -> appText(R.string.msg_52420fc5327e)
        else -> appText(R.string.msg_cc93f0020b76)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { stateDescription = status })
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        val caption = if (finished) appText(R.string.msg_a527127a2bc6) else progress?.expectedEndAt?.let {
            appText(R.string.msg_0b6e2d0e2cdc , SimpleDateFormat("HH:mm", AppStrings.locale()).format(Date(it)))
        } ?: appText(R.string.msg_900e6be64ccc)
        Text(caption, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1)
    }
}
