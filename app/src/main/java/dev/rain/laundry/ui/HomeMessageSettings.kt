package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import dev.rain.laundry.ui.ControlIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val DEFAULT_HOME_MESSAGE = "今天，也要干干净净。"
internal const val MAX_HOME_MESSAGE_LENGTH = 80

/** Number of Unicode code points in [raw], including spaces and line breaks. */
internal fun homeMessageLength(raw: String): Int {
    var index = 0
    var count = 0
    while (index < raw.length) {
        index += Character.charCount(raw.codePointAt(index))
        count++
    }
    return count
}

/** Trim outer whitespace, fall back to the default when blank, and cap at 80 code points. */
internal fun normalizeHomeMessage(raw: String?): String {
    val trimmed = raw.orEmpty().trim()
    if (trimmed.isEmpty()) return DEFAULT_HOME_MESSAGE
    return trimmed.takeCodePoints(MAX_HOME_MESSAGE_LENGTH)
}

/** Localize only the built-in greeting; a user-written greeting is always displayed verbatim. */
internal fun displayHomeMessage(raw: String?): String = normalizeHomeMessage(raw).let {
    if (it == DEFAULT_HOME_MESSAGE) localizedMessage(DEFAULT_HOME_MESSAGE) else it
}

internal fun homeMessageFromDraft(raw: String): String {
    val normalized = normalizeHomeMessage(raw)
    return if (normalized == localizedMessage(DEFAULT_HOME_MESSAGE)) DEFAULT_HOME_MESSAGE else normalized
}

private fun String.takeCodePoints(max: Int): String {
    if (max <= 0) return ""
    var index = 0
    var count = 0
    while (index < length && count < max) {
        index += Character.charCount(codePointAt(index))
        count++
    }
    return substring(0, index)
}

/** One tappable row for the theme settings group, with an edit dialog for the home greeting. */
@Composable
internal fun HomeMessageSettings(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val current = displayHomeMessage(settings.homeMessage)
    val spokenCurrent = current.replace('\n', ' ').replace('\r', ' ')

    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = appText(R.string.msg_a367880426a2), role = Role.Button) {
                draft = current
                editing = true
            }
            .semantics(mergeDescendants = true) {
                contentDescription = appText(R.string.msg_85312acee487 , spokenCurrent)
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(34.dp).background(colors.surfaceContainerHigh.controlContainer(), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Home, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(appText(R.string.msg_92863c1ad1f0), style = MaterialTheme.typography.bodyLarge)
            Text(
                current,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                localize = false
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            null,
            Modifier.size(20.dp),
            tint = colors.onSurfaceVariant
        )
    }

    if (editing) {
        HomeMessageDialog(
            draft = draft,
            onDraftChange = { draft = it },
            onSave = {
                onChange(settings.copy(homeMessage = homeMessageFromDraft(draft)))
                editing = false
            },
            onReset = {
                onChange(settings.copy(homeMessage = DEFAULT_HOME_MESSAGE))
                editing = false
            },
            onDismiss = { editing = false }
        )
    }
}

@Composable
private fun HomeMessageDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appText(R.string.msg_a367880426a2)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    appText(R.string.msg_7f96511c0fa3 , MAX_HOME_MESSAGE_LENGTH),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                AppOutlinedTextField(
                    value = draft,
                    onValueChange = { onDraftChange(it.takeCodePoints(MAX_HOME_MESSAGE_LENGTH)) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = appText(R.string.msg_41c71d37f309 , MAX_HOME_MESSAGE_LENGTH)
                    },
                    labelText = appText(R.string.msg_37f64a4b3e45),
                    label = { Text(appText(R.string.msg_37f64a4b3e45)) },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                    supportingText = {
                        Text(
                            "${homeMessageLength(draft)}/$MAX_HOME_MESSAGE_LENGTH · " +
                                appText(R.string.msg_40ca838c072f)
                        )
                    }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        appText(R.string.msg_542e9ae16505 , localizedMessage(DEFAULT_HOME_MESSAGE)),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    AppTextButton(
                        onClick = onReset,
                        modifier = Modifier.semantics { contentDescription = appText(R.string.msg_a0640541affa) }
                    ) { Text(appText(R.string.msg_ba2e93e73037)) }
                }
            }
        },
        confirmButton = {
            AppTextButton(
                onClick = onSave,
                modifier = Modifier.semantics { contentDescription = appText(R.string.msg_37bacd3c428f) }
            ) { Text(appText(R.string.msg_a3030bf8f16d)) }
        },
        dismissButton = {
            AppTextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = appText(R.string.msg_d0032305b571) }
            ) { Text(appText(R.string.msg_2cd0f3be8738)) }
        }
    )
}
