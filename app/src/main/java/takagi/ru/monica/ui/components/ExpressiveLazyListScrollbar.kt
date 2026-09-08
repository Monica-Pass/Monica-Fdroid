package takagi.ru.monica.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun expressiveScrollbarMaxFirstIndex(totalItems: Int, visibleItems: Int): Int =
    (totalItems - visibleItems.coerceAtLeast(1)).coerceAtLeast(1)

internal fun expressiveScrollbarTargetIndex(
    progress: Float,
    totalItems: Int,
    visibleItems: Int,
): Int = (progress.coerceIn(0f, 1f) * expressiveScrollbarMaxFirstIndex(totalItems, visibleItems))
    .roundToInt()
    .coerceIn(0, (totalItems - 1).coerceAtLeast(0))

private data class ExpressiveScrollbarMetrics(
    val progress: Float,
    val totalItems: Int,
    val maxScrollIndex: Int,
    val travelPx: Float,
)

/**
 * Measured item-stride tracker for variable-height cards.
 *
 * Observations accumulate per index instead of folding into a running average. [observe] runs
 * inside the metrics snapshot producer, so it has to be idempotent: a history-dependent estimate
 * makes the same layout yield different progress values and the handle jitters during a fling.
 */
internal class ExpressiveScrollbarAxisTracker {
    private var trackedTotalItems = -1
    private var trackedSpacingPx = Int.MIN_VALUE
    private val observedStrides = mutableMapOf<Int, Float>()
    private val observedItemSizes = mutableMapOf<Int, Float>()
    private var strideSum = 0f
    private var itemSizeSum = 0f

    fun resetIfNeeded(totalItems: Int, spacingPx: Int) {
        if (trackedTotalItems == totalItems && trackedSpacingPx == spacingPx) return
        trackedTotalItems = totalItems
        trackedSpacingPx = spacingPx
        observedStrides.clear()
        observedItemSizes.clear()
        strideSum = 0f
        itemSizeSum = 0f
    }

    fun observe(layout: LazyListLayoutInfo) {
        resetIfNeeded(layout.totalItemsCount, layout.mainAxisItemSpacing)
        val visible = layout.visibleItemsInfo
        if (visible.isEmpty()) return
        visible.forEach { item ->
            val size = item.size.toFloat()
            itemSizeSum += size - (observedItemSizes.put(item.index, size) ?: 0f)
        }
        visible.zipWithNext().forEach { (current, next) ->
            if (next.index != current.index + 1) return@forEach
            val stride = (next.offset - current.offset).toFloat()
            if (stride <= 0f) return@forEach
            strideSum += stride - (observedStrides.put(current.index, stride) ?: 0f)
        }
    }

    private fun averageStride(): Float = if (observedStrides.isEmpty()) {
        averageItemSize()
    } else {
        (strideSum / observedStrides.size).coerceAtLeast(1f)
    }

    private fun averageItemSize(): Float = if (observedItemSizes.isEmpty()) {
        1f
    } else {
        (itemSizeSum / observedItemSizes.size).coerceAtLeast(1f)
    }

    /** Measured strides where known, mean elsewhere; monotonically increasing in [index]. */
    fun distanceBefore(index: Int): Float {
        if (index <= 0) return 0f
        val average = averageStride()
        val correction = observedStrides.entries.sumOf { (observedIndex, stride) ->
            if (observedIndex < index) (stride - average).toDouble() else 0.0
        }.toFloat()
        return (index * average + correction).coerceAtLeast(0f)
    }

    fun itemSize(index: Int): Float = observedItemSizes[index] ?: averageItemSize()
    fun stride(): Float = averageStride()
}

private fun expressiveScrollbarMetrics(
    listState: LazyListState,
    tracker: ExpressiveScrollbarAxisTracker,
    availableHeightPx: Float,
    handleHeightPx: Float,
): ExpressiveScrollbarMetrics {
    val layout = listState.layoutInfo
    val visible = layout.visibleItemsInfo
    val totalItems = layout.totalItemsCount
    val travelPx = (availableHeightPx - handleHeightPx).coerceAtLeast(1f)
    if (visible.isEmpty() || totalItems <= 1) {
        return ExpressiveScrollbarMetrics(0f, totalItems, 1, travelPx)
    }
    tracker.observe(layout)
    val viewportPx = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat().coerceAtLeast(1f)
    val currentScrollPx = tracker.distanceBefore(listState.firstVisibleItemIndex) + listState.firstVisibleItemScrollOffset
    val lastIndex = totalItems - 1
    val totalScrollPx = (
        layout.beforeContentPadding + layout.afterContentPadding +
            tracker.distanceBefore(lastIndex) + tracker.itemSize(lastIndex) - viewportPx
        ).coerceAtLeast(1f)
    val estimatedVisibleItems = (viewportPx / tracker.stride()).coerceAtLeast(1f)
    val maxScrollIndex = (totalItems - estimatedVisibleItems).toInt().coerceAtLeast(1)
    val progress = when {
        !listState.canScrollBackward -> 0f
        !listState.canScrollForward -> 1f
        else -> (currentScrollPx / totalScrollPx).coerceIn(0f, 1f)
    }
    return ExpressiveScrollbarMetrics(progress, totalItems, maxScrollIndex, travelPx)
}

@Composable
fun ExpressiveLazyListScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    labelForIndex: (Int) -> String? = { null },
    onInteractionChange: (Boolean) -> Unit = {},
) {
    val canScroll by remember(listState) {
        derivedStateOf { listState.canScrollBackward || listState.canScrollForward }
    }
    if (!canScroll) return

    val density = LocalDensity.current
    val collapsedWidth = 7.dp
    val expandedWidth = 28.dp
    val handleHeight = 52.dp
    val endPadding = 4.dp
    val interactionWidth = expandedWidth + endPadding
    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var pendingTargetIndex by remember { mutableIntStateOf(-1) }
    var retainedLabel by remember { mutableStateOf<String?>(null) }
    val metricsTracker = remember(listState) { ExpressiveScrollbarAxisTracker() }
    val displayedProgress = remember(listState) { Animatable(0f) }
    var hasSyncedDisplayedProgress by remember(listState) { mutableStateOf(false) }
    val interactionCallback by rememberUpdatedState(onInteractionChange)

    LaunchedEffect(isPressed, isDragging) {
        interactionCallback(isPressed || isDragging)
    }
    DisposableEffect(Unit) {
        onDispose { interactionCallback(false) }
    }

    val smoothJumpMinDistancePx = with(density) { 10.dp.toPx() }
    val displayedWidth by animateDpAsState(
        targetValue = if (isPressed || isDragging) expandedWidth else collapsedWidth,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "vaultScrollbarWidth",
    )
    val handleIconAlpha by animateFloatAsState(
        targetValue = if (isPressed || isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "vaultScrollbarIcon",
    )
    val activeLabel = if (isDragging && pendingTargetIndex >= 0) {
        labelForIndex(pendingTargetIndex)
    } else {
        null
    }
    LaunchedEffect(activeLabel) {
        if (!activeLabel.isNullOrBlank()) retainedLabel = activeLabel
    }
    val labelAlpha by animateFloatAsState(
        targetValue = if (isDragging && !activeLabel.isNullOrBlank()) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "vaultScrollbarLabelAlpha",
    )
    val labelScale by animateFloatAsState(
        targetValue = if (isDragging && !activeLabel.isNullOrBlank()) 1f else 0.84f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "vaultScrollbarLabelScale",
    )

    LaunchedEffect(listState) {
        snapshotFlow { pendingTargetIndex }
            .distinctUntilChanged()
            .collectLatest { targetIndex ->
                if (targetIndex >= 0) listState.scrollToItem(targetIndex)
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(interactionWidth),
    ) {
        val heightPx = with(density) { maxHeight.toPx() }
        val handleHeightPx = with(density) { handleHeight.toPx() }
        val travelPx = (heightPx - handleHeightPx).coerceAtLeast(1f)

        LaunchedEffect(listState, metricsTracker, heightPx, handleHeightPx, isDragging) {
            if (isDragging) return@LaunchedEffect
            snapshotFlow {
                expressiveScrollbarMetrics(listState, metricsTracker, heightPx, handleHeightPx)
            }
                .distinctUntilChanged()
                .collectLatest { metrics ->
                    if (!hasSyncedDisplayedProgress) {
                        displayedProgress.snapTo(metrics.progress)
                        hasSyncedDisplayedProgress = true
                    } else {
                        val deltaPx = abs(metrics.progress - displayedProgress.value) * metrics.travelPx
                        if (!listState.isScrollInProgress && deltaPx >= smoothJumpMinDistancePx) {
                            displayedProgress.animateTo(metrics.progress, tween(70, easing = FastOutSlowInEasing))
                        } else {
                            displayedProgress.snapTo(metrics.progress)
                        }
                    }
                }
        }

        LaunchedEffect(isDragging, dragProgress) {
            if (isDragging) {
                displayedProgress.snapTo(dragProgress)
                hasSyncedDisplayedProgress = true
            }
        }

        fun updateFromTouch(touchY: Float, grabOffset: Float) {
            val nextProgress = ((touchY - grabOffset) / travelPx).coerceIn(0f, 1f)
            dragProgress = nextProgress
            val metrics = expressiveScrollbarMetrics(listState, metricsTracker, heightPx, handleHeightPx)
            pendingTargetIndex = (nextProgress * metrics.maxScrollIndex)
                .toInt()
                .coerceIn(0, (metrics.totalItems - 1).coerceAtLeast(0))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            try {
                                awaitRelease()
                            } finally {
                                isPressed = false
                            }
                        },
                    )
                }
                .pointerInput(listState) {
                    var grabOffset = handleHeightPx / 2f
                    detectDragGestures(
                        onDragStart = { position ->
                            isDragging = true
                            dragProgress = displayedProgress.value
                            val currentTop = displayedProgress.value * travelPx
                            grabOffset = if (position.y in currentTop..(currentTop + handleHeightPx)) {
                                position.y - currentTop
                            } else {
                                handleHeightPx / 2f
                            }
                            updateFromTouch(position.y, grabOffset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            updateFromTouch(change.position.y, grabOffset)
                        },
                        onDragEnd = {
                            isDragging = false
                            pendingTargetIndex = -1
                        },
                        onDragCancel = {
                            isDragging = false
                            pendingTargetIndex = -1
                        },
                    )
                },
        ) {
            val indicatorPath = remember { Path() }
            val trackColor = MaterialTheme.colorScheme.secondaryContainer
            val indicatorColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val endPaddingPx = endPadding.toPx()
                val collapsedWidthPx = collapsedWidth.toPx()
                val indicatorWidthPx = displayedWidth.toPx()
                val handleTop = displayedProgress.value * travelPx
                val anchorX = size.width - endPaddingPx
                val trackX = anchorX - collapsedWidthPx / 2f
                val gapPx = 7.dp.toPx()

                if (handleTop > gapPx) {
                    drawLine(
                        color = trackColor,
                        start = Offset(trackX, 0f),
                        end = Offset(trackX, handleTop - gapPx),
                        strokeWidth = collapsedWidthPx,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                if (handleTop + handleHeightPx + gapPx < size.height) {
                    drawLine(
                        color = trackColor,
                        start = Offset(trackX, handleTop + handleHeightPx + gapPx),
                        end = Offset(trackX, size.height),
                        strokeWidth = collapsedWidthPx,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }

                val leftRadius = indicatorWidthPx / 2f
                val rightRadius = 6.dp.toPx().coerceAtMost(leftRadius)
                indicatorPath.reset()
                indicatorPath.addRoundRect(
                    RoundRect(
                        rect = Rect(
                            offset = Offset(anchorX - indicatorWidthPx, handleTop),
                            size = Size(indicatorWidthPx, handleHeightPx),
                        ),
                        topLeft = CornerRadius(leftRadius, leftRadius),
                        bottomLeft = CornerRadius(leftRadius, leftRadius),
                        topRight = CornerRadius(rightRadius, rightRadius),
                        bottomRight = CornerRadius(rightRadius, rightRadius),
                    ),
                )
                drawPath(indicatorPath, indicatorColor)
            }

            if (handleIconAlpha > 0f) {
                Icon(
                    imageVector = Icons.Rounded.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            val widthPx = with(density) { displayedWidth.toPx() }
                            IntOffset(
                                x = -with(density) { endPadding.toPx() }.roundToInt() -
                                    ((widthPx - with(density) { 24.dp.toPx() }) / 2f).roundToInt(),
                                y = (displayedProgress.value * travelPx +
                                    (handleHeightPx - with(density) { 24.dp.toPx() }) / 2f).roundToInt(),
                            )
                        }
                        .size(24.dp)
                        .graphicsLayer {
                            alpha = handleIconAlpha
                            scaleX = handleIconAlpha
                            scaleY = handleIconAlpha
                        },
                )
            }

            val label = activeLabel ?: retainedLabel
            if (labelAlpha > 0f && !label.isNullOrBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = -with(density) { (interactionWidth + 10.dp).toPx() }.roundToInt(),
                                y = (displayedProgress.value * travelPx +
                                    (handleHeightPx - with(density) { 44.dp.toPx() }) / 2f).roundToInt(),
                            )
                        }
                        .size(44.dp)
                        .graphicsLayer {
                            alpha = labelAlpha
                            scaleX = labelScale
                            scaleY = labelScale
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
