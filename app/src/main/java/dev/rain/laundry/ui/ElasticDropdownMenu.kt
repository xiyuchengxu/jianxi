package dev.rain.laundry.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** The popup stays mounted through its closing spring, so quick reversals stay continuous. */
@Composable
internal fun ElasticDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    val transition = rememberTransition(visible, label = "styleMenu")
    val scale = transition.animateFloat(transitionSpec = {
        if (targetState) spring(dampingRatio = .55f, stiffness = 480f)
        else spring(dampingRatio = .78f, stiffness = 900f)
    }, label = "styleMenuScale") { if (it) 1f else .52f }
    val heightScale = transition.animateFloat(transitionSpec = {
        if (targetState) spring(dampingRatio = .58f, stiffness = 380f)
        else spring(dampingRatio = .82f, stiffness = 1000f)
    }, label = "styleMenuHeight") { if (it) 1f else .34f }
    val opacity = transition.animateFloat(transitionSpec = { tween(if (targetState) 90 else 110) },
        label = "styleMenuAlpha") { if (it) 1f else 0f }
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    var above by remember { mutableStateOf(false) }
    val position = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val below = anchorBounds.bottom - margin
                val fitsBelow = below + popupContentSize.height <= windowSize.height - margin
                above = !fitsBelow && anchorBounds.top >= popupContentSize.height
                val y = if (above) anchorBounds.top - popupContentSize.height + margin else below
                val x = if (layoutDirection == LayoutDirection.Ltr)
                    anchorBounds.right - popupContentSize.width + margin else anchorBounds.left - margin
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    if (visible.currentState || visible.targetState) {
        Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true, clippingEnabled = false)) {
            // Padding gives the overshoot and shadow room inside the popup window.
            Surface(Modifier.padding(12.dp).width(180.dp).graphicsLayer {
                transformOrigin = TransformOrigin(1f, if (above) 1f else 0f)
                scaleX = scale.value; scaleY = heightScale.value
                alpha = opacity.value.coerceIn(0f, 1f)
            }.semantics { paneTitle = "配色风格" },
                shape = RoundedCornerShape(23.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
                shadowElevation = 9.dp) {
                Column(Modifier.padding(vertical = 8.dp), content = content)
            }
        }
    }
}
