package app.livetoframe.personal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
internal fun galleryRangeSelection(
    grid: LazyGridState,
    photos: List<GalleryPhoto>,
    selected: Set<String>,
    enabled: Boolean,
    setRange: (Set<String>, List<String>, Boolean) -> Unit,
): Modifier {
    val positions = remember(photos) { photos.mapIndexed { index, photo -> photo.key to index }.toMap() }
    val currentSelection by rememberUpdatedState(selected)
    val updateRange by rememberUpdatedState(setRange)
    if (!enabled) return Modifier
    return Modifier.pointerInput(grid, positions) {
        fun indexAt(position: Offset): Int? {
            val layout = grid.layoutInfo
            val y = position.y + layout.viewportStartOffset
            return layout.visibleItemsInfo.firstNotNullOfOrNull { item ->
                val key = item.key as? String ?: return@firstNotNullOfOrNull null
                positions[key]?.takeIf {
                    position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
                        y >= item.offset.y && y < item.offset.y + item.size.height
                }
            }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val base = currentSelection
            val start = indexAt(down.position) ?: return@awaitEachGesture
            var active = false
            var previous = -1
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
                if (!active) {
                    if (!change.pressed) break
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    if (dy >= viewConfiguration.touchSlop && dy >= dx / 1.2f) break
                    if (dx < viewConfiguration.touchSlop || dx <= dy * 1.2f) continue
                    active = true
                }
                change.consume()
                val end = indexAt(change.position) ?: if (previous < 0) start else continue
                if (end != previous) {
                    previous = end
                    val selecting = photos[start].key !in base
                    updateRange(base, photos.subList(minOf(start, end), maxOf(start, end) + 1).map { it.key }, selecting)
                }
            } while (change.pressed)
        }
    }
}
