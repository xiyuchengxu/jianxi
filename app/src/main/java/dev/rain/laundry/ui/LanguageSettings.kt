package dev.rain.laundry.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rain.laundry.R
import dev.rain.laundry.i18n.AppLanguage
import dev.rain.laundry.i18n.LanguagePreferences
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.notifications.LaundryReminders

@Composable
internal fun LanguageSettings(enabled: Boolean) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = LanguagePreferences.selected(context)
    val title = appText(R.string.msg_9f6fee1aba17)
    fun label(language: AppLanguage) = if (language == AppLanguage.SYSTEM)
        appText(R.string.msg_217cfe7db1e3) else language.nativeName
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }
        .semantics { contentDescription = title }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(label(selected), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(appText(R.string.msg_b90a8ac9c488)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(Modifier.fillMaxWidth().selectable(selected == language, role = Role.RadioButton) {
                        open = false
                        if (selected != language) {
                            LanguagePreferences.set(context, language)
                            LaundryReminders.refreshLanguage(context)
                            if (Build.VERSION.SDK_INT < 33) context.activity()?.recreate()
                        }
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == language, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(language))
                    }
                }
                Text(appText(R.string.msg_4741fddebad0), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(localizedMessage("关闭")) } })
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
