package app.livetoframe.personal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal data class GalleryScrollRow(val itemIndex: Int, val date: LocalDate?)

@Composable
internal fun GalleryDateScrollbar(
    rows: List<GalleryScrollRow>,
    grid: LazyGridState,
    modifier: Modifier,
    scrollTouchActive: Boolean,
    dateTitle: (LocalDate?) -> String,
) {
    val scrollable by remember(grid) { derivedStateOf { grid.canScrollForward || grid.canScrollBackward } }
    if (rows.size < 2 || !scrollable) return
    val lastRow = rows.lastIndex
    val visibleRow by remember(grid, rows) {
        derivedStateOf {
            if (!grid.canScrollForward) lastRow
            else rows.binarySearchBy(grid.firstVisibleItemIndex) { it.itemIndex }
                .let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        }
    }
    var shown by remember { mutableStateOf(false) }
    var touched by remember(rows) { mutableStateOf(false) }
    var draggedFraction by remember(rows) { mutableStateOf<Float?>(null) }
    val galleryTouch by rememberUpdatedState(scrollTouchActive)
    LaunchedEffect(grid) {
        snapshotFlow { grid.isScrollInProgress || touched || galleryTouch }.collectLatest { active ->
            if (active) shown = true
            else if (shown) {
                delay(1350)
                shown = false
            }
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(160), label = "fast scroll handle")
    if (!shown && alpha == 0f) return

    val fraction = draggedFraction ?: visibleRow.toFloat() / lastRow
    val currentRow = (fraction * lastRow).roundToInt()
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val travel = (maxHeight - 56.dp).coerceAtLeast(1.dp)
        val travelPixels by rememberUpdatedState(with(LocalDensity.current) { travel.toPx() })
        val startFraction by rememberUpdatedState(fraction)
        val top = 4.dp + travel * fraction
        if (draggedFraction != null) {
            Text(dateTitle(rows[currentRow].date),
                Modifier.align(Alignment.TopEnd).offset(x = (-46).dp, y = top + 8.dp)
                    .shadow(5.dp, CircleShape).background(colors.surface, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Box(Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = top)
            .size(38.dp, 48.dp).graphicsLayer { this.alpha = alpha }
            .pointerInput(rows, grid) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    touched = true
                    var displacement = 0f
                    val initial = startFraction
                    var lastRequested = visibleRow
                    try {
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            displacement += change.positionChange().y
                            if (draggedFraction != null || abs(displacement) >= viewConfiguration.touchSlop / 2) {
                                val progress = (initial + displacement / travelPixels).coerceIn(0f, 1f)
                                draggedFraction = progress
                                val row = (progress * lastRow).roundToInt()
                                if (row != lastRequested) {
                                    grid.requestScrollToItem(rows[row].itemIndex)
                                    lastRequested = row
                                }
                            }
                            change.consume()
                        } while (change.pressed)
                    } finally {
                        draggedFraction = null
                        touched = false
                    }
                }
            }.then(if (shown) Modifier.semantics {
                contentDescription = "按日期快滚"
                stateDescription = dateTitle(rows[currentRow].date)
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value ->
                    grid.requestScrollToItem(rows[(lastRow * value.coerceIn(0f, 1f)).roundToInt()].itemIndex)
                    true
                }
            } else Modifier), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(33.dp, 46.dp).shadow(5.dp, CircleShape).background(colors.surface, CircleShape)) {
                val x = size.width / 2
                val mid = size.height / 2
                val w = 5.dp.toPx()
                val gap = 2.dp.toPx()
                val height = 5.dp.toPx()
                drawPath(Path().apply {
                    moveTo(x - w, mid - gap)
                    lineTo(x + w, mid - gap)
                    lineTo(x, mid - gap - height)
                    close()
                    moveTo(x - w, mid + gap)
                    lineTo(x + w, mid + gap)
                    lineTo(x, mid + gap + height)
                    close()
                }, ink)
            }
        }
    }
}
