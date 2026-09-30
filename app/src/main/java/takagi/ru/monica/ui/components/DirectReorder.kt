package takagi.ru.monica.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.Scroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import takagi.ru.monica.ui.LocalReduceAnimations
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

/** Shared by customization rows and password content, including repeated content blocks. */
@Composable
internal fun directReorderShape(index: Int, count: Int, draggedIndex: Int?): RoundedCornerShape {
    val reducedMotion = LocalReduceAnimations.current
    val lifted = draggedIndex == index
    val openTop = lifted || (draggedIndex != null && index == draggedIndex + 1)
    val openBottom = lifted || (draggedIndex != null && index == draggedIndex - 1)
    // Only the lifted card and its facing neighbour edges separate. Other rows stay joined.
    fun spec(open: Boolean) = if (reducedMotion) snap<Dp>() else tween<Dp>(180, delayMillis = if (open) 0 else 240)
    val top by animateDpAsState(if (openTop || index == 0) 24.dp else 4.dp, spec(openTop), label = "reorderTop")
    val bottom by animateDpAsState(if (openBottom || index == count - 1) 24.dp else 4.dp, spec(openBottom), label = "reorderBottom")
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Stable
internal class DirectDragState { var key by mutableStateOf<String?>(null) }
internal data class ContentDrag(val modifier: Modifier = Modifier, val dragging: Boolean = false, val draggedIndex: Int? = null)
internal val LocalContentDrag = compositionLocalOf { ContentDrag() }

/** Reorderable 3.0 starts its scroll coroutine before sending the first request.
 * An immediate dispatcher can finish that coroutine with an empty channel and leave a
 * completed job installed forever. Queue its launch so the request is sent first.
 * This is the only internal API used; remove when upstream fixes Scroller.start().
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@Composable
internal fun rememberDirectReorderScroller(list: LazyListState): Scroller {
    val parent = rememberCoroutineScope()
    return remember(list, parent) {
        Scroller(list, CoroutineScope(parent.coroutineContext + Dispatchers.Main)) {
            list.layoutInfo.viewportSize.height * 0.5f
        }
    }
}

internal fun entryContentItemKey(token: String): String =
    if (PasswordContentSection.entries.any { it.name == token }) "content_${token.lowercase(java.util.Locale.ROOT)}" else token

/** Retains the editor's lazy list, stable keys, and edge auto-scroll. Never creates nested scroll areas. */
internal fun LazyListScope.reorderableContentItem(
    key: String,
    state: ReorderableLazyListState,
    enabled: Boolean,
    dragState: DirectDragState,
    groupKeys: List<String>,
    content: @Composable LazyItemScope.() -> Unit,
) {
    item(key = key) {
        if (!enabled) {
            content()
        } else {
            val itemScope = this
            val feedback = rememberHapticFeedback()
            val reducedMotion = LocalReduceAnimations.current
            ReorderableItem(state, key,
                animateItemModifier = if (reducedMotion) Modifier else Modifier.animateItem()) { dragging ->
                val dragModifier = Modifier.longPressDraggableHandle(
                    onDragStarted = { dragState.key = key; feedback.performLongPress() },
                    onDragStopped = { dragState.key = null; feedback.performLightClick() },
                )
                val draggedIndex = groupKeys.indexOf(dragState.key).takeIf { it >= 0 }
                CompositionLocalProvider(LocalContentDrag provides ContentDrag(dragModifier, dragging, draggedIndex)) {
                    itemScope.content()
                }
            }
        }
    }
}

/** Visual slot while ReorderableColumn animates its draft order before onSettle. */
internal fun directReorderVisualIndex(index: Int, from: Int, to: Int): Int = when {
    from < 0 || to < 0 -> index
    index == from -> to
    from < to && index in (from + 1)..to -> index - 1
    to < from && index in to until from -> index + 1
    else -> index
}

internal fun directReorderTarget(from: Int, delta: Float, heights: List<Int>, spacing: Float): Int {
    if (from !in heights.indices) return from
    val starts = heights.runningFold(0f) { total, h -> total + h + spacing }
    val start = starts[from]
    val end = start + heights[from]
    return if (delta > 0) heights.indices.lastOrNull { it != from && starts[it] + heights[it] / 2f in end..(end + delta) } ?: from
        else heights.indices.firstOrNull { it != from && starts[it] + heights[it] / 2f in (start + delta)..start } ?: from
}
