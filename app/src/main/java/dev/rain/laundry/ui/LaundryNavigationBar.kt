package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.*
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rain.laundry.core.NavigationTab
import dev.rain.laundry.core.visibleNavigationTabs
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val NavigationTab.icon get() = when (this) {
    NavigationTab.LAUNDRY -> Icons.Outlined.LocalLaundryService
    NavigationTab.WATER -> Icons.Outlined.WaterDrop
    NavigationTab.ORDERS -> Icons.AutoMirrored.Outlined.ReceiptLong
    NavigationTab.SETTINGS -> Icons.Outlined.Settings
}

@Composable
fun LaundryNavigationBar(
    layer: GraphicsLayer,
    selected: Int,
    onSelect: (Int) -> Unit,
    style: BottomBarStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glassLevel: GlassLevel = GlassLevel.FULL,
    tabs: List<NavigationTab> = visibleNavigationTabs(),
    dryerOnly: Boolean = false
) {
    val floating = style == BottomBarStyle.FLOATING
    val barModifier = if (floating) modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 6.dp) else modifier
    AdjustableControl("navigation.bottom", barModifier, deferUntilUp = floating) { target ->
        if (style == BottomBarStyle.FIXED && LocalMiuixStyle.current) {
            val latestEnabled = rememberUpdatedState(enabled)
            val latestSelect = rememberUpdatedState(onSelect)
            val choose: (Int) -> Unit = { index ->
                if (latestEnabled.value) latestSelect.value(index)
            }
            val labels = tabs.map { tab ->
                if (dryerOnly && tab == NavigationTab.LAUNDRY) appText(R.string.saved_device_kind_dryer)
                else localizedMessage(tab.title)
            }
            Box(target.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer.controlContainer())
                .controlForegroundOutline()) {
                MiuixNavigationBar(items = tabs.mapIndexed { index, tab -> NavigationItem(labels[index], tab.icon) },
                    selected = selected, onClick = choose, color = Color.Transparent,
                    modifier = Modifier.clearAndSetSemantics {})
                // This library release supplies pointer-only navigation. Accessible selectable targets
                // preserve the app's enabled, selected and announced-tab behavior over its native visuals.
                Row(Modifier.fillMaxWidth().height(64.dp).align(Alignment.TopCenter)) {
                    tabs.forEachIndexed { index, _ ->
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .selectable(selected = selected == index, enabled = enabled, role = Role.Tab,
                                onClick = { choose(index) })
                            .semantics { contentDescription = appText(R.string.msg_8ffbad496815, labels[index]) })
                    }
                }
            }
            return@AdjustableControl
        }
        when (style) {
            BottomBarStyle.FLOATING -> GlassNavigationBar(layer, selected, onSelect,
                target, enabled, glassLevel,
                tabs = tabs, dryerOnly = dryerOnly)
            BottomBarStyle.FIXED -> NavigationBar(
                modifier = target.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainer.controlContainer(),
                tonalElevation = 0.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = NavigationBarItemDefaults.colors().selectedIndicatorColor.controlContainer()),
                        enabled = enabled, modifier = Modifier.semantics { contentDescription = appText(R.string.msg_8ffbad496815 ,
                            if (dryerOnly && tab == NavigationTab.LAUNDRY) appText(R.string.saved_device_kind_dryer)
                            else localizedMessage(tab.title)) },
                        icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.controlForegroundOutline()) },
                        label = { Text(if (dryerOnly && tab == NavigationTab.LAUNDRY) appText(R.string.saved_device_kind_dryer) else tab.title,
                            modifier = Modifier.controlForegroundOutline()) })
                }
            }
        }
    }
}
