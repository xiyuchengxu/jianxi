package dev.rain.laundry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.rain.laundry.ui.ControlIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rain.laundry.R
import dev.rain.laundry.core.SavedDevice
import dev.rain.laundry.core.SavedDeviceKind
import dev.rain.laundry.core.SavedDevices
import dev.rain.laundry.core.SavedDeviceStatus
import dev.rain.laundry.core.SavedDeviceStatusPhase
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import java.text.SimpleDateFormat
import java.util.Date

/**
 * A prominent, count-aware shortcut shown on both the laundry home page and the water page.
 * The list is filtered by [kind] so one entry can never count, show, or select the other business.
 */
@Composable
internal fun SavedDevicesEntry(
    kind: SavedDeviceKind,
    devices: List<SavedDevice>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = remember(devices, kind) { SavedDevices.ofKind(devices, kind) }
    AppOutlinedCard(
        controlId = "saved.${kind.wire}.entry", onClick = onClick,
        enabled = enabled,
        shape = appShape(24.dp),
        modifier = modifier.fillMaxWidth().testTag("savedDevicesEntry")
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.controlContainer()) {
                Icon(
                    when (kind) {
                        SavedDeviceKind.WATER -> Icons.Outlined.WaterDrop
                        SavedDeviceKind.DRYER -> Icons.Outlined.LocalLaundryService
                        SavedDeviceKind.LAUNDRY -> Icons.Outlined.LocalLaundryService
                    },
                    null,
                    Modifier.padding(10.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).controlGlyphContrast(MaterialTheme.colorScheme.onSurface),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(appText(kindTitleRes(kind)), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (visible.isEmpty()) appText(kindEntryEmptyRes(kind))
                    else appText(kindEntryCountRes(kind), visible.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedDevicesSheet(
    kind: SavedDeviceKind,
    devices: List<SavedDevice>,
    busy: Boolean,
    message: String?,
    onSelect: (SavedDevice) -> Unit,
    onDelete: (SavedDevice) -> Unit,
    onRename: (SavedDevice, String) -> Unit,
    onDismiss: () -> Unit,
    status: SavedDeviceStatus? = null,
    statuses: Map<String, SavedDeviceStatus> = emptyMap()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visible = remember(devices, kind) { SavedDevices.ofKind(devices, kind) }
    var renameTarget by remember { mutableStateOf<SavedDevice?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedDevice?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appText(kindTitleRes(kind)), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                AppIconButton(onClick = onDismiss, enabled = !busy) {
                    Icon(Icons.Outlined.Close, appText(R.string.saved_device_close))
                }
            }
            Text(appText(kindHintRes(kind)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let {
                Text(localizedMessage(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (visible.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        when (kind) {
                            SavedDeviceKind.WATER -> Icons.Outlined.WaterDrop
                            SavedDeviceKind.DRYER -> Icons.Outlined.LocalLaundryService
                            SavedDeviceKind.LAUNDRY -> Icons.Outlined.LocalLaundryService
                        },
                        null, Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text(appText(kindEmptyTitleRes(kind)), style = MaterialTheme.typography.titleMedium)
                    Text(appText(kindEmptyBodyRes(kind)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("savedDeviceList"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.kind + ":" + it.deviceId }) { device ->
                        val rowStatus = if (kind == SavedDeviceKind.LAUNDRY) {
                            statuses[SavedDeviceStatus.key(device)]?.takeIf { it.matches(device) }
                                ?: status?.takeIf { it.matches(device) }
                        } else null
                        SavedDeviceRow(device, busy, rowStatus,
                            select = { if (device.savedKind == kind) onSelect(device) },
                            rename = { renameTarget = device },
                            delete = { deleteTarget = device })
                    }
                }
            }
            AppOutlinedButton(onClick = onDismiss, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(appText(R.string.saved_device_close))
            }
        }
    }
    renameTarget?.let { target ->
        var note by remember(target) { mutableStateOf(target.note) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(appText(R.string.saved_device_rename_title)) },
            text = {
                AppOutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(SavedDevices.MAX_NOTE_LENGTH) },
                    labelText = appText(R.string.saved_device_note_label),
                    label = { Text(appText(R.string.saved_device_note_label)) },
                    placeholder = { Text(appText(R.string.saved_device_note_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                AppTextButton(onClick = { onRename(target, note); renameTarget = null }, enabled = !busy) {
                    Text(appText(R.string.saved_device_save))
                }
            },
            dismissButton = {
                AppTextButton(onClick = { renameTarget = null }) {
                    Text(appText(R.string.saved_device_cancel))
                }
            }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(appText(R.string.saved_device_delete_title)) },
            text = { Text(appText(R.string.saved_device_delete_body)) },
            confirmButton = {
                AppTextButton(onClick = { onDelete(target); deleteTarget = null }, enabled = !busy) {
                    Text(appText(R.string.saved_device_delete))
                }
            },
            dismissButton = {
                AppTextButton(onClick = { deleteTarget = null }) {
                    Text(appText(R.string.saved_device_cancel))
                }
            }
        )
    }
}

@Composable
private fun SavedDeviceRow(
    device: SavedDevice,
    busy: Boolean,
    status: SavedDeviceStatus?,
    select: () -> Unit,
    rename: () -> Unit,
    delete: () -> Unit
) {
    val kind = device.savedKind
    val rowId = privateControlId("saved.row", "${device.owner}\u0000${device.kind}\u0000${device.codeKey}\u0000${device.codeValue}")
    AppOutlinedCard(controlId = rowId,
        onClick = select,
        enabled = !busy,
        shape = appShape(20.dp),
        modifier = Modifier.fillMaxWidth().testTag("savedDeviceRow")
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (kind) {
                    SavedDeviceKind.WATER -> Icons.Outlined.WaterDrop
                    SavedDeviceKind.DRYER -> Icons.Outlined.LocalLaundryService
                    SavedDeviceKind.LAUNDRY -> Icons.Outlined.LocalLaundryService
                    null -> Icons.Outlined.LocalLaundryService
                },
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    device.note.ifBlank { device.name }.ifBlank { kindLabel(kind) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = buildList {
                    add(kindLabel(kind))
                    if (device.note.isNotBlank() && device.name.isNotBlank()) add(device.name)
                }.filter { it.isNotBlank() }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (kind == SavedDeviceKind.LAUNDRY) status?.takeIf { it.matches(device) }?.let {
                    SavedDeviceStatusContent(it, compact = true)
                    if (it.phase != SavedDeviceStatusPhase.CHECKING && it.checkedAt > 0L) {
                        Text(appText(R.string.saved_status_checked_at,
                            SimpleDateFormat("HH:mm:ss", AppStrings.locale()).format(Date(it.checkedAt))),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(appText(R.string.saved_device_last_used, formatSavedTime(device.lastUsedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIconButton(controlId = "$rowId.rename", onClick = rename, enabled = !busy) {
                Icon(Icons.Outlined.Edit, appText(R.string.saved_device_rename), Modifier.size(20.dp))
            }
            AppIconButton(controlId = "$rowId.delete", onClick = delete, enabled = !busy) {
                Icon(Icons.Outlined.Delete, appText(R.string.saved_device_delete), Modifier.size(20.dp))
            }
        }
    }
}

/** Callers on a device page first match the snapshot to that page's owner/kind/device record. */
@Composable
internal fun SavedDeviceStatusCard(status: SavedDeviceStatus?) {
    if (status == null) return
    AdjustableSurface("device.status", shape = appShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("savedDeviceStatus")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(appText(R.string.saved_status_title), style = MaterialTheme.typography.titleSmall)
            SavedDeviceStatusContent(status, compact = false)
        }
    }
}

@Composable
private fun SavedDeviceStatusContent(status: SavedDeviceStatus, compact: Boolean) {
    if (compact) {
        if (status.kind != SavedDeviceKind.LAUNDRY) return
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("savedDeviceListStatus")) {
            Box(Modifier.size(8.dp).background(savedDeviceListStatusColor(status.phase), CircleShape)
                .testTag("savedDeviceStatusDot"))
            Text(savedDeviceListStatusLabel(status), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("savedDeviceStatusLabel"))
        }
        return
    }
    val color = when (status.phase) {
        SavedDeviceStatusPhase.ERROR, SavedDeviceStatusPhase.UNAVAILABLE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(savedDeviceStatusLabel(status), style = MaterialTheme.typography.bodySmall, color = color,
        modifier = Modifier.testTag("savedDeviceStatusLabel"))
    if (status.phase == SavedDeviceStatusPhase.CHECKING) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    if (status.subject.isNotBlank()) Text(appText(
        if (status.kind == SavedDeviceKind.LAUNDRY) R.string.saved_status_mode else R.string.saved_status_outlet,
        status.subject), style = MaterialTheme.typography.labelSmall, color = color)
    if (status.remainingMinutes > 0) Text(appText(R.string.saved_status_remaining, status.remainingMinutes),
        style = MaterialTheme.typography.labelSmall, color = color)
    if (status.detail.isNotBlank()) Text(localizedMessage(status.detail),
        style = MaterialTheme.typography.bodySmall, color = color)
    if (status.checkedAt > 0L) Text(appText(R.string.saved_status_checked_at,
        SimpleDateFormat("HH:mm:ss", AppStrings.locale()).format(Date(status.checkedAt))),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!compact) Text(appText(R.string.saved_status_snapshot_hint), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Only a confirmed available observation is green. Pending or unknown observations stay neutral. */
internal fun savedDeviceListStatusColor(phase: SavedDeviceStatusPhase): Color = when (phase) {
    SavedDeviceStatusPhase.AVAILABLE -> Color(0xFF2E7D32)
    SavedDeviceStatusPhase.USING, SavedDeviceStatusPhase.OCCUPIED,
    SavedDeviceStatusPhase.UNAVAILABLE, SavedDeviceStatusPhase.ERROR -> Color(0xFFC62828)
    SavedDeviceStatusPhase.CHECKING, SavedDeviceStatusPhase.UNKNOWN -> Color(0xFF757575)
}

internal fun savedDeviceListStatusLabel(status: SavedDeviceStatus): String = appText(when (status.phase) {
    SavedDeviceStatusPhase.AVAILABLE -> R.string.saved_status_list_available
    SavedDeviceStatusPhase.USING, SavedDeviceStatusPhase.OCCUPIED,
    SavedDeviceStatusPhase.UNAVAILABLE -> R.string.saved_status_list_unavailable
    SavedDeviceStatusPhase.CHECKING -> R.string.saved_status_list_checking
    SavedDeviceStatusPhase.UNKNOWN -> R.string.saved_status_list_unknown
    SavedDeviceStatusPhase.ERROR -> R.string.saved_status_list_failed
})

internal fun savedDeviceStatusLabel(status: SavedDeviceStatus): String = appText(when (status.phase) {
    SavedDeviceStatusPhase.CHECKING -> R.string.saved_status_checking
    SavedDeviceStatusPhase.AVAILABLE -> if (status.kind == SavedDeviceKind.LAUNDRY)
        R.string.saved_status_laundry_available else R.string.saved_status_water_available
    SavedDeviceStatusPhase.USING -> if (status.kind == SavedDeviceKind.LAUNDRY)
        R.string.saved_status_laundry_using else R.string.saved_status_water_using
    SavedDeviceStatusPhase.OCCUPIED -> R.string.saved_status_occupied
    SavedDeviceStatusPhase.UNAVAILABLE -> R.string.saved_status_unavailable
    SavedDeviceStatusPhase.UNKNOWN -> R.string.saved_status_unknown
    SavedDeviceStatusPhase.ERROR -> R.string.saved_status_failed
})

private fun kindLabel(kind: SavedDeviceKind?): String = when (kind) {
    SavedDeviceKind.LAUNDRY -> appText(R.string.saved_device_kind_laundry)
    SavedDeviceKind.DRYER -> appText(R.string.saved_device_kind_dryer)
    SavedDeviceKind.WATER -> appText(R.string.saved_device_kind_water)
    null -> appText(R.string.saved_device_invalid)
}

private fun kindTitleRes(kind: SavedDeviceKind): Int = when (kind) {
    SavedDeviceKind.LAUNDRY -> R.string.saved_device_title_laundry
    SavedDeviceKind.DRYER -> R.string.saved_device_title_dryer
    SavedDeviceKind.WATER -> R.string.saved_device_title_water
}

private fun kindHintRes(kind: SavedDeviceKind): Int = when (kind) {
    SavedDeviceKind.LAUNDRY -> R.string.saved_device_hint_laundry
    SavedDeviceKind.DRYER -> R.string.saved_device_hint_dryer
    SavedDeviceKind.WATER -> R.string.saved_device_hint_water
}

private fun kindEntryEmptyRes(kind: SavedDeviceKind): Int = when (kind) {
    SavedDeviceKind.LAUNDRY -> R.string.saved_device_entry_empty_laundry
    SavedDeviceKind.DRYER -> R.string.saved_device_entry_empty_dryer
    SavedDeviceKind.WATER -> R.string.saved_device_entry_empty_water
}

private fun kindEntryCountRes(kind: SavedDeviceKind): Int = when (kind) {
    SavedDeviceKind.LAUNDRY -> R.string.saved_device_entry_count_laundry
    SavedDeviceKind.DRYER -> R.string.saved_device_entry_count_dryer
    SavedDeviceKind.WATER -> R.string.saved_device_entry_count_water
}

private fun kindEmptyTitleRes(kind: SavedDeviceKind): Int = when (kind) {
    SavedDeviceKind.LAUNDRY -> R.string.saved_device_empty_title_laundry
    SavedDeviceKind.DRYER -> R.string.saved_device_empty_title_dryer
    SavedDeviceKind.WATER -> R.string.saved_device_empty_title_water
}

private fun kindEmptyBodyRes(kind: SavedDeviceKind): Int = when (kind) {
    SavedDeviceKind.LAUNDRY -> R.string.saved_device_empty_body_laundry
    SavedDeviceKind.DRYER -> R.string.saved_device_empty_body_dryer
    SavedDeviceKind.WATER -> R.string.saved_device_empty_body_water
}

private fun formatSavedTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", AppStrings.locale()).format(Date(time))
