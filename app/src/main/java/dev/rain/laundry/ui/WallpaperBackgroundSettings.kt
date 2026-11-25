package dev.rain.laundry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.rain.laundry.R
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.LocalizedText as Text
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults as MiuixSliderDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Window wallpaper controls are independent of wallpaper-derived palette selection. */
@Composable
internal fun WallpaperBackgroundSettings(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit) {
    val currentSettings = rememberUpdatedState(settings)
    val currentChange = rememberUpdatedState(onChange)
    val supported = rememberSystemWallpaperSupported()
    val currentSupport = rememberUpdatedState(supported)
    val colors = MaterialTheme.colorScheme
    val value = normalizeWallpaperBrightness(settings.wallpaperBackgroundBrightness)
    val enabled = settings.wallpaperBackgroundEnabled && supported
    val percent = (value * 100).roundToInt()
    val description = appText(R.string.wallpaper_background_brightness)
    val adjustment = when {
        percent < 0 -> appText(R.string.wallpaper_background_dimmed, abs(percent))
        percent > 0 -> appText(R.string.wallpaper_background_brightened, percent)
        else -> appText(R.string.wallpaper_background_original)
    }
    fun brightness(requested: Float) {
        val latest = currentSettings.value
        if (latest.wallpaperBackgroundEnabled && currentSupport.value && requested.isFinite())
            currentChange.value(latest.copy(wallpaperBackgroundBrightness = requested.coerceIn(-1f, 1f)))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Wallpaper, null, tint = colors.onSurfaceVariant)
        Text(appText(R.string.wallpaper_background_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        AppSwitch(checked = enabled,
            controlId = "theme.wallpaper.switch",
            enabled = supported,
            onCheckedChange = {
                if (currentSupport.value)
                    currentChange.value(currentSettings.value.copy(wallpaperBackgroundEnabled = it))
            },
            modifier = Modifier.testTag("wallpaperBackgroundSwitch")
                .semantics { contentDescription = appText(R.string.wallpaper_background_title) })
    }
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!supported) Text(appText(R.string.wallpaper_background_unsupported),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Text(adjustment,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        AppearanceSlider(value, -1f..1f, enabled, description, "wallpaperBackgroundBrightness", ::brightness)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(appText(R.string.wallpaper_background_darker), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
            Text("0", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(appText(R.string.wallpaper_background_lighter), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
        AppTextButton(controlId = "theme.wallpaper.resetBrightness", onClick = { brightness(0f) }, enabled = enabled && value != 0f,
            modifier = Modifier.align(Alignment.End).testTag("wallpaperBackgroundReset")) {
            Text(appText(R.string.wallpaper_background_reset))
        }
    }
    SettingDivider()
    val transparency = settings.effectiveControlTransparency(enabled)
    val label = appText(R.string.control_transparency)
    val locale = LocalConfiguration.current.locales[0]
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(percentFormat.format(transparency.toDouble()), style = MaterialTheme.typography.bodySmall)
        }
        AppearanceSlider(transparency, 0f..1f, true, label, "controlTransparency") { requested ->
            currentChange.value(currentSettings.value.copy(controlTransparency = requested))
        }
        Text(appText(R.string.control_long_press_hint), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        AppTextButton(controlId = "theme.controls.resetTransparency", onClick = { currentChange.value(currentSettings.value.copy(controlTransparency = null)) },
            enabled = settings.controlTransparency != null, modifier = Modifier.align(Alignment.End)) {
            Text(appText(R.string.msg_ba2e93e73037))
        }
    }
}

@Composable
internal fun AppearanceSlider(value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    description: String, tag: String, onChange: (Float) -> Unit) {
    val modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = description }
    AdjustableControl("theme.slider.$tag", modifier) { target ->
        if (LocalMiuixStyle.current) {
            MiuixSlider(progress = value, onProgressChange = onChange, enabled = enabled,
                minValue = range.start, maxValue = range.endInclusive, decimalPlaces = 2,
                colors = MiuixSliderDefaults.sliderColors(backgroundColor = MiuixTheme.colorScheme.tertiaryContainerVariant.controlContainer()),
                modifier = target.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                    if (!enabled) disabled()
                    setProgress { requested ->
                        if (!enabled || !requested.isFinite()) false
                        else { onChange(requested.coerceIn(range)); true }
                    }
                })
        } else {
            val colors = SliderDefaults.colors()
            Slider(value = value, onValueChange = onChange, enabled = enabled, valueRange = range, modifier = target,
                colors = colors.copy(inactiveTrackColor = colors.inactiveTrackColor.controlContainer(),
                    disabledInactiveTrackColor = colors.disabledInactiveTrackColor.controlContainer()))
        }
    }
}
