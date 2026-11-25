package dev.rain.laundry.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Initial pass runs from outer to inner. By Main pass the innermost hit has registered its claim. */
internal class ControlLongPressCoordinator {
    var candidate: Any? = null
}

internal val LocalControlLongPressCoordinator = staticCompositionLocalOf<ControlLongPressCoordinator?> { null }

@Composable
internal fun Modifier.controlTransparencyLongPress(
    label: String,
    gestureKey: String,
    edgeOnly: Boolean = false,
    deferUntilUp: Boolean = false,
    onLongPress: () -> Unit
): Modifier {
    val coordinator = LocalControlLongPressCoordinator.current ?: return this
    val latest = rememberUpdatedState(onLongPress)
    return semantics {
        onLongClick(label) { latest.value(); true }
    }.pointerInput(coordinator, gestureKey, edgeOnly, deferUntilUp) {
        val self = Any()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Interior touches of an editable field reserve text selection, including inside an editable card.
            val edgeWidth = minOf(20.dp.toPx(), size.width / 3f)
            val editableHere = !edgeOnly || down.position.x >= size.width - edgeWidth
            coordinator.candidate = if (editableHere) self else null
            awaitPointerEvent(PointerEventPass.Main)
            if (coordinator.candidate !== self) return@awaitEachGesture

            val slop = viewConfiguration.touchSlop
            val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                do {
                    val point = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: break
                    if (point.id != down.id || !point.pressed || (point.position - down.position).getDistance() > slop) break
                } while (true)
                true
            }
            if (endedEarly == true || coordinator.candidate !== self) return@awaitEachGesture

            if (deferUntilUp) {
                // A glass navigation tab can be held indefinitely before a horizontal drag.
                while (true) {
                    val point = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: return@awaitEachGesture
                    if (point.id != down.id || (point.position - down.position).getDistance() > slop) return@awaitEachGesture
                    if (!point.pressed) {
                        if (!point.isConsumed) {
                            point.consume()
                            latest.value()
                        }
                        break
                    }
                }
            } else {
                latest.value()
                // Once the editor opens, suppress release before any descendant can run its ordinary click.
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}
