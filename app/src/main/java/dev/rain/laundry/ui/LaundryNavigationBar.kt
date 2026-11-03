package dev.rain.laundry.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.dp

internal val laundryTabs = listOf(
    "洗衣" to Icons.Outlined.LocalLaundryService,
    "订单" to Icons.AutoMirrored.Outlined.ReceiptLong,
    "主题" to Icons.Outlined.Palette
)

@Composable
fun LaundryNavigationBar(
    layer: GraphicsLayer,
    selected: Int,
    onSelect: (Int) -> Unit,
    style: BottomBarStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glassLevel: GlassLevel = GlassLevel.FULL
) {
    when (style) {
        BottomBarStyle.FLOATING -> GlassNavigationBar(layer, selected, onSelect,
            modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 6.dp), enabled, glassLevel)
        BottomBarStyle.FIXED -> NavigationBar(
            modifier = modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            laundryTabs.forEachIndexed { index, (title, icon) ->
                NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
                    enabled = enabled, icon = { Icon(icon, contentDescription = null) }, label = { Text(title) })
            }
        }
    }
}
