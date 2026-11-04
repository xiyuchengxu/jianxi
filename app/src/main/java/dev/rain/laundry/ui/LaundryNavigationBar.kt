package dev.rain.laundry.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rain.laundry.core.NavigationTab
import dev.rain.laundry.core.visibleNavigationTabs

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
    tabs: List<NavigationTab> = visibleNavigationTabs()
) {
    when (style) {
        BottomBarStyle.FLOATING -> GlassNavigationBar(layer, selected, onSelect,
            modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 6.dp), enabled, glassLevel, tabs = tabs)
        BottomBarStyle.FIXED -> NavigationBar(
            modifier = modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
                    enabled = enabled, modifier = Modifier.semantics { contentDescription = "${tab.title}导航" },
                    icon = { Icon(tab.icon, contentDescription = null) }, label = { Text(tab.title) })
            }
        }
    }
}
