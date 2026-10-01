package takagi.ru.monica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.Job
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordPageContentType

internal data class PasswordQuickFilterChipState(
    val favorite: Boolean,
    val twoFa: Boolean,
    val notes: Boolean,
    val passkey: Boolean,
    val boundNote: Boolean,
    val attachments: Boolean,
    val uncategorized: Boolean,
    val localOnly: Boolean,
    val manualStackOnly: Boolean,
    val neverStack: Boolean,
    val unstacked: Boolean,
    val aggregateSelectedTypes: Set<PasswordPageContentType>,
    val aggregateVisibleTypes: List<PasswordPageContentType>
)

internal data class PasswordQuickFilterChipCallbacks(
    val onFavoriteChange: (Boolean) -> Unit,
    val onTwoFaChange: (Boolean) -> Unit,
    val onNotesChange: (Boolean) -> Unit,
    val onPasskeyChange: (Boolean) -> Unit,
    val onBoundNoteChange: (Boolean) -> Unit,
    val onAttachmentsChange: (Boolean) -> Unit,
    val onUncategorizedChange: (Boolean) -> Unit,
    val onLocalOnlyChange: (Boolean) -> Unit,
    val onManualStackOnlyChange: (Boolean) -> Unit,
    val onNeverStackChange: (Boolean) -> Unit,
    val onUnstackedChange: (Boolean) -> Unit,
    val onToggleAggregateType: (PasswordPageContentType) -> Unit
)

internal data class PasswordQuickFilterEditGridParams(
    val items: List<PasswordListQuickFilterItem>,
    val chipState: PasswordQuickFilterChipState,
    val chipCallbacks: PasswordQuickFilterChipCallbacks,
    val onOrderCommitted: (List<PasswordListQuickFilterItem>) -> Unit,
    val editing: Boolean = true
)

internal fun mergeVisibleQuickFilterOrder(
    fullOrder: List<PasswordListQuickFilterItem>,
    reorderedVisibleItems: List<PasswordListQuickFilterItem>
): List<PasswordListQuickFilterItem> {
    if (reorderedVisibleItems.isEmpty()) return fullOrder
    val visibleSet = reorderedVisibleItems.toSet()
    val reordered = reorderedVisibleItems.iterator()
    return fullOrder.map { item ->
        if (item in visibleSet && reordered.hasNext()) reordered.next() else item
    }
}

@Composable
private fun PasswordQuickFilterEditItem(
    item: PasswordListQuickFilterItem,
    params: PasswordQuickFilterEditGridParams,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        PasswordQuickFilterChipItem(
            item = item,
            categoryEditMode = params.editing,
            quickFilterFavorite = params.chipState.favorite,
            onQuickFilterFavoriteChange = params.chipCallbacks.onFavoriteChange,
            quickFilter2fa = params.chipState.twoFa,
            onQuickFilter2faChange = params.chipCallbacks.onTwoFaChange,
            quickFilterNotes = params.chipState.notes,
            onQuickFilterNotesChange = params.chipCallbacks.onNotesChange,
            quickFilterPasskey = params.chipState.passkey,
            onQuickFilterPasskeyChange = params.chipCallbacks.onPasskeyChange,
            quickFilterBoundNote = params.chipState.boundNote,
            onQuickFilterBoundNoteChange = params.chipCallbacks.onBoundNoteChange,
            quickFilterAttachments = params.chipState.attachments,
            onQuickFilterAttachmentsChange = params.chipCallbacks.onAttachmentsChange,
            quickFilterUncategorized = params.chipState.uncategorized,
            onQuickFilterUncategorizedChange = params.chipCallbacks.onUncategorizedChange,
            quickFilterLocalOnly = params.chipState.localOnly,
            onQuickFilterLocalOnlyChange = params.chipCallbacks.onLocalOnlyChange,
            quickFilterManualStackOnly = params.chipState.manualStackOnly,
            onQuickFilterManualStackOnlyChange = params.chipCallbacks.onManualStackOnlyChange,
            quickFilterNeverStack = params.chipState.neverStack,
            onQuickFilterNeverStackChange = params.chipCallbacks.onNeverStackChange,
            quickFilterUnstacked = params.chipState.unstacked,
            onQuickFilterUnstackedChange = params.chipCallbacks.onUnstackedChange,
            aggregateSelectedTypes = params.chipState.aggregateSelectedTypes,
            aggregateVisibleTypes = params.chipState.aggregateVisibleTypes,
            onToggleAggregateType = params.chipCallbacks.onToggleAggregateType,
            modifier = Modifier
        )

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordQuickFilterEditGrid(params: PasswordQuickFilterEditGridParams) {
    val visibleItems = remember(params.items, params.chipState.aggregateVisibleTypes) {
        params.items.filter { shouldShowQuickFilterItem(it, params.chipState.aggregateVisibleTypes) }
    }
    val latestParams by rememberUpdatedState(params)
    var localOrder by remember { mutableStateOf(visibleItems) }
    var dragging by remember { mutableStateOf<PasswordListQuickFilterItem?>(null) }
    var visualOrigin by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var previousPointer by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { mutableStateMapOf<PasswordListQuickFilterItem, Rect>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(visibleItems) {
        if (dragging == null) localOrder = visibleItems
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        localOrder.forEach { item ->
            key(item) {
                val placement = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
                var placementJob by remember { mutableStateOf<Job?>(null) }
                val active = dragging == item
                // Measure the untransformed slot; the inner layer alone follows the finger.
                Box(Modifier
                    .zIndex(if (active) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        val next = Rect(coordinates.positionInParent(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                        val previous = bounds.put(item, next)
                        if (previous != null && previous.topLeft != next.topLeft && dragging != null && dragging != item) {
                            val delta = previous.topLeft - next.topLeft + placement.value
                            placementJob?.cancel()
                            placementJob = scope.launch {
                                placement.snapTo(delta)
                                placement.animateTo(Offset.Zero, spring())
                            }
                        }
                    }
                    .testTag("quick_filter_" + item.name)
                    .pointerInput(params.editing) {
                        if (!params.editing) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { point ->
                                bounds[item]?.let { rect ->
                                    placementJob?.cancel()
                                    dragging = item
                                    grabOffset = point
                                    visualOrigin = rect.topLeft
                                    previousPointer = rect.topLeft + point
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onDrag = { change, _ ->
                                if (dragging == item) {
                                    change.consume()
                                    val pointer = (bounds[item]?.topLeft ?: Offset.Zero) + change.position
                                    visualOrigin = pointer - grabOffset
                                    if ((pointer - previousPointer).getDistance() > 1f) {
                                        previousPointer = pointer
                                        if (bounds[item]?.contains(pointer) != true) {
                                            val target = localOrder.firstOrNull {
                                                it != item && bounds[it]?.contains(pointer) == true
                                            }
                                            if (target != null) {
                                                localOrder = localOrder.toMutableList().apply {
                                                    val from = indexOf(item)
                                                    val to = indexOf(target)
                                                    add(to, removeAt(from))
                                                }
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                val delta = visualOrigin - (bounds[item]?.topLeft ?: visualOrigin)
                                placementJob?.cancel()
                                placementJob = scope.launch {
                                    placement.snapTo(delta)
                                    dragging = null
                                    placement.animateTo(Offset.Zero, spring())
                                }
                                val merged = mergeVisibleQuickFilterOrder(latestParams.items, localOrder)
                                if (merged != latestParams.items) latestParams.onOrderCommitted(merged)
                            },
                            onDragCancel = {
                                dragging = null
                                localOrder = latestParams.items.filter {
                                    shouldShowQuickFilterItem(it, latestParams.chipState.aggregateVisibleTypes)
                                }
                            },
                        )
                    }
                ) {
                    PasswordQuickFilterEditItem(item, params, Modifier.graphicsLayer {
                        val offset = if (active) visualOrigin - (bounds[item]?.topLeft ?: visualOrigin)
                            else if (params.editing) placement.value else Offset.Zero
                        translationX = offset.x
                        translationY = offset.y
                        shadowElevation = if (active) 6.dp.toPx() else 0f
                        shape = RoundedCornerShape(20.dp)
                    }.testTag("quick_filter_visual_" + item.name))
                }
            }
        }
    }
}
