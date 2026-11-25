package dev.rain.laundry.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.rain.laundry.R
import dev.rain.laundry.i18n.appText
import java.text.NumberFormat

private class IndividualTransparency(val preferences: SharedPreferences) {
    private val values = mutableStateMapOf<String, Float>()

    fun reload() {
        values.clear()
        for ((id, value) in preferences.all) {
            normalizeControlTransparency(value as? Float)?.let { values[id] = it }
        }
    }

    fun refresh(id: String) {
        val value = normalizeControlTransparency(runCatching { preferences.getFloat(id, Float.NaN) }.getOrNull())
        if (value == null) values.remove(id) else values[id] = value
    }

    operator fun get(id: String): Float? = values[id]

    fun set(id: String, transparency: Float?) {
        val value = normalizeControlTransparency(transparency)
        if (values[id] == value) return
        if (value == null) {
            values.remove(id)
            preferences.edit().remove(id).apply()
        } else {
            values[id] = value
            preferences.edit().putFloat(id, value).apply()
        }
    }
}

private class ControlEditor(val values: IndividualTransparency, val globalOpacity: Float, val edit: (String) -> Unit)
private val LocalControlEditor = compositionLocalOf<ControlEditor?> { null }
internal val LocalControlHasOverride = compositionLocalOf { false }

@Composable
internal fun AdjustableControl(id: String, modifier: Modifier, edgeOnly: Boolean = false,
    deferUntilUp: Boolean = false, content: @Composable (Modifier) -> Unit) {
    val editor = LocalControlEditor.current
    if (editor == null) {
        content(modifier)
        return
    }
    val custom = editor.values[id]
    // Each nested control inherits the app-wide value independently of the card surrounding it.
    val opacity = custom?.let { 1f - it } ?: editor.globalOpacity
    CompositionLocalProvider(LocalControlOpacity provides opacity, LocalControlHasOverride provides (custom != null)) {
        content(modifier.controlTransparencyLongPress(appText(R.string.control_adjust_individual), id,
            edgeOnly = edgeOnly, deferUntilUp = deferUntilUp) { editor.edit(id) })
    }
}

@Composable
internal fun ControlTransparencyHost(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) {
        IndividualTransparency(context.getSharedPreferences("control_transparency_overrides", Context.MODE_PRIVATE))
    }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, id ->
            if (id == null) store.reload() else store.refresh(id)
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        store.reload()
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val globalOpacity = LocalControlOpacity.current
    val editor = remember(store, globalOpacity) { ControlEditor(store, globalOpacity) { editing = it } }
    CompositionLocalProvider(LocalControlEditor provides editor,
        LocalControlLongPressCoordinator provides remember { ControlLongPressCoordinator() }, content = content)
    editing?.let { id ->
        // The editor stays legible even when the target is entirely transparent.
        CompositionLocalProvider(LocalControlOpacity provides 1f) {
            ControlTransparencySheet(store[id] ?: (1f - globalOpacity),
                onChange = { store.set(id, it) },
                followGlobal = { store.set(id, null); editing = null },
                onDismiss = { editing = null })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlTransparencySheet(value: Float, onChange: (Float) -> Unit, followGlobal: () -> Unit, onDismiss: () -> Unit) {
    val title = appText(R.string.control_transparency)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getPercentInstance(locale) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        scrimColor = Color.Black.copy(alpha = .16f)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(format.format(value.toDouble()), style = MaterialTheme.typography.bodyMedium)
            }
            AppearanceSlider(value, 0f..1f, true, title, "individualControlTransparency", onChange)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppTextButton(onClick = followGlobal) { Text(appText(R.string.control_follow_global)) }
                AppButton(onClick = onDismiss) { Text(appText(R.string.control_done)) }
            }
        }
    }
}
