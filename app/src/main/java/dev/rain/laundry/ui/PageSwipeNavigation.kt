package dev.rain.laundry.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Runs on the content viewport. The scrollable child gets first chance to consume vertical drags. */
@Composable
internal fun Modifier.pageSwipeNavigation(enabled: Boolean, selected: Int, onSelect: (Int) -> Unit): Modifier {
    val currentOnSelect by rememberUpdatedState(onSelect)
    return pointerInput(enabled, selected) {
        if (!enabled) return@pointerInput
        var distance = 0f
        val threshold = (size.width * .18f).coerceIn(56.dp.toPx(), 96.dp.toPx())
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onHorizontalDrag = { _, delta -> distance += delta },
            onDragEnd = {
                if (abs(distance) >= threshold) {
                    val target = selected + if (distance < 0f) 1 else -1
                    if (target in laundryTabs.indices) currentOnSelect(target)
                }
                distance = 0f
            }
        )
    }
}
