package app.voyahradio.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/** The range, offset and extent a View reports for its scroll bar. */
internal data class ScrollMetrics(val range: Int, val offset: Int, val extent: Int)

/** Scroll bar arithmetic of the View system: AbsListView's smooth scroll bar and ScrollBarUtils. */
internal object ViewScrollbars {
    /** A row in view: its top and bottom relative to the top of the list. */
    data class Row(val index: Int, val top: Int, val bottom: Int)

    /** AbsListView with a smooth scroll bar over [count] rows, of which [rows] are on screen in a list [height] tall. */
    fun list(count: Int, rows: List<Row>, height: Int): ScrollMetrics {
        if (rows.isEmpty() || height <= 0) return ScrollMetrics(maxOf(count * 100, 0), 0, 0)
        val first = rows.first()
        val last = rows.last()
        var extent = rows.size * 100
        val firstHeight = first.bottom - first.top
        if (firstHeight > 0) extent += first.top * 100 / firstHeight
        val lastHeight = last.bottom - last.top
        if (lastHeight > 0) extent -= (last.bottom - height) * 100 / lastHeight
        val offset = if (firstHeight > 0) maxOf(first.index * 100 - first.top * 100 / firstHeight, 0) else 0
        return ScrollMetrics(maxOf(count * 100, 0), offset, extent)
    }

    /** ScrollView: the child's height, the scroll position and the view's height. */
    fun scroll(contentHeight: Int, scrollY: Int, height: Int) = ScrollMetrics(contentHeight, scrollY, height)

    /** The thumb's offset and length on a track of [size] pixels and [thickness] wide, or null without a thumb. */
    fun thumb(metrics: ScrollMetrics, size: Int, thickness: Int): Pair<Int, Int>? {
        val (range, offset, extent) = metrics
        if (range <= extent || size <= 0) return null
        val length = maxOf((size.toFloat() * extent / range).roundToInt(), thickness * 2)
        val position = minOf(((size - length).toFloat() / (range - extent) * offset).roundToInt(), size - length)
        return position to length
    }
}

/** The fading scroll bar thumb of a dark Material View: white at 36 % behind 4 dp, at the right edge. */
private val ThumbInk = Color(1f, 1f, 1f, 0xB3 / 255f * 0x84 / 255f)
private const val FADE_DELAY_MS = 400L
private const val FADE_MS = 250

/** A ListView's scroll bar over a list whose bottom padding is [bottomPadding] pixels. */
@Composable
internal fun Modifier.listScrollbar(state: LazyListState, count: Int, bottomPadding: Int): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.drop(1).collectLatest { awaken(alpha) }
    }
    return drawWithContent {
        drawContent()
        if (alpha.value <= 0f) return@drawWithContent
        val info = state.layoutInfo
        val rows = info.visibleItemsInfo.map { ViewScrollbars.Row(it.index, it.offset, it.offset + it.size) }
        val metrics = ViewScrollbars.list(count, rows, size.height.toInt())
        val thickness = px(4)
        val track = size.height.toInt() - bottomPadding
        val (top, length) = ViewScrollbars.thumb(metrics, track, thickness) ?: return@drawWithContent
        drawRect(ThumbInk, Offset(size.width - thickness, top.toFloat()), Size(thickness.toFloat(), length.toFloat()),
            alpha = alpha.value)
    }
}

/** A ScrollView's scroll bar. */
@Composable
internal fun Modifier.scrollViewScrollbar(state: ScrollState): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.value }.drop(1).collectLatest { awaken(alpha) }
    }
    return drawWithContent {
        drawContent()
        if (alpha.value <= 0f) return@drawWithContent
        val height = size.height.toInt()
        val metrics = ViewScrollbars.scroll(height + state.maxValue, state.value, height)
        val thickness = px(4)
        val (top, length) = ViewScrollbars.thumb(metrics, height, thickness) ?: return@drawWithContent
        drawRect(ThumbInk, Offset(size.width - thickness, top.toFloat()), Size(thickness.toFloat(), length.toFloat()),
            alpha = alpha.value)
    }
}

/** View.awakenScrollBars: the thumb shows at once, stays 400 ms and fades out in 250 ms. */
private suspend fun awaken(alpha: Animatable<Float, AnimationVector1D>) {
    alpha.snapTo(1f)
    delay(FADE_DELAY_MS)
    alpha.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
}

