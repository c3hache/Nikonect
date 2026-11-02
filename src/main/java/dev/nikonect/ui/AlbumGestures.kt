package dev.nikonect.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

internal fun LazyGridState.keyAt(point: Offset): String? = layoutInfo.visibleItemsInfo
    .firstOrNull { point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
        point.y >= it.offset.y && point.y < it.offset.y + it.size.height }?.key as? String

/** Let ordinary taps and one-finger scrolling through. Own the stream only after a
 * stationary long press or a second contact, before a child can interpret the release. */
internal fun Modifier.albumGestures(grid: LazyGridState, enabled: Boolean,
    zoom: (Int) -> Unit, start: (String) -> Boolean, move: (Offset?) -> Unit) =
    pointerInput(grid, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Initial)
            val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var pinch = false
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    pinch = e.changes.count { it.pressed } >= 2
                    if (pinch || e.changes.none { it.pressed } ||
                        (e.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                pinch
            }
            if (interrupted == true) {
                var factor = 1f
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.changes.count { it.pressed } > 1) {
                        factor *= e.calculateZoom()
                        if (factor > 1.23f || factor < .81f) {
                            zoom(if (factor > 1f) -1 else 1)
                            factor = 1f
                        }
                    }
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
            } else if (interrupted == null && grid.keyAt(down.position)?.let(start) == true) {
                try {
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val finger = e.changes.first()
                        if (finger.pressed) move(finger.position)
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                } finally { move(null) }
            }
        }
    }
