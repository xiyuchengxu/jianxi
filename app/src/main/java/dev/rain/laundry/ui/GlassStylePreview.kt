package dev.rain.laundry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A local scene, sampled by the same renderer as the real navigation bar; no login needed. */
@Composable
internal fun GlassStylePreview(level: GlassLevel, tabs: List<dev.rain.laundry.core.NavigationTab>) {
    val colors = MaterialTheme.colorScheme
    val backdrop = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var selected by remember(tabs) { mutableIntStateOf(tabs.lastIndex) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("悬浮底栏交互预览", Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(20.dp))
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics { contentDescription = "液态玻璃${level.title}交互预览" }) {
            Column(Modifier.matchParentSize().then(if (level != GlassLevel.NONE) Modifier.drawWithContent {
                backdrop.record { this@drawWithContent.drawContent() }; drawLayer(backdrop)
            } else Modifier).background(Brush.linearGradient(listOf(
                colors.primaryContainer, colors.surfaceContainerLow, colors.tertiaryContainer)))
                .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("长按 · 拖动 · 松手", style = MaterialTheme.typography.labelMedium,
                    color = colors.onPrimaryContainer.copy(alpha = .65f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(3) { index -> Text("${index + 1}  简洗", style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface.copy(alpha = .4f)) }
                }
            }
            GlassNavigationBar(backdrop, selected, { selected = it },
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 4.dp),
                glassLevel = level, backdropOrigin = origin, tabs = tabs)
        }
    }
}
