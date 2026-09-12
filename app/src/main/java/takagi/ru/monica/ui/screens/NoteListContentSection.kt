package takagi.ru.monica.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Note
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.notes.ui.model.NoteListItemUiModel
import takagi.ru.monica.ui.components.PullActionVisualState
import takagi.ru.monica.ui.common.state.InitialListRenderState
import takagi.ru.monica.ui.common.state.rememberSaveableLazyListState
import takagi.ru.monica.ui.common.state.rememberSaveableLazyGridState
import takagi.ru.monica.ui.common.state.resolveMergedListRenderState
import takagi.ru.monica.ui.common.pull.rememberPullActionState
import takagi.ru.monica.ui.common.pull.PullSearchDefaults
import takagi.ru.monica.ui.common.pull.PullSearchHint

@Composable
fun NoteListContent(
    notes: List<NoteListItemUiModel>,
    isInitialLoading: Boolean,
    isGridLayout: Boolean,
    isSearchExpanded: Boolean,
    onRequestExpandSearch: () -> Unit,
    isBitwardenDatabaseView: Boolean,
    bitwardenRepository: BitwardenRepository,
    selectedNoteIds: Set<Long>,
    allNotes: List<NoteListItemUiModel> = notes,
    onUpdateSortOrders: (List<Pair<Long, Int>>) -> Unit = {},
    onNoteClick: (Long) -> Unit,
    onNoteLongClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val listState = rememberSaveableLazyListState()
    val gridState = rememberSaveableLazyGridState()
    val searchTriggerDistance = remember(density, isBitwardenDatabaseView) {
        with(density) { (if (isBitwardenDatabaseView) 40.dp else PullSearchDefaults.TriggerDistance).toPx() }
    }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val pullAction = rememberPullActionState(
        isBitwardenDatabaseView = isBitwardenDatabaseView,
        isSearchExpanded = isSearchExpanded,
        searchTriggerDistance = searchTriggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        maxDragDistance = maxDragDistance,
        bitwardenRepository = bitwardenRepository,
        onSearchTriggered = { onRequestExpandSearch() },
    )
    val currentOffset = pullAction.currentOffset

    val emptyStateGestureModifier = Modifier.pointerInput(isSearchExpanded) {
        detectVerticalDragGestures(
            onVerticalDrag = { _, dragAmount -> pullAction.onVerticalDrag(dragAmount) },
            onDragEnd = pullAction.onDragEnd,
            onDragCancel = pullAction.onDragCancel,
        )
    }

    val contentPullOffset = currentOffset.toInt()
    // Do not display cached notes until their saved scope is ready.
    val initialRenderState = resolveMergedListRenderState(
        isReady = !isInitialLoading,
        itemCount = notes.size,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        PullSearchHint(currentOffset = currentOffset, triggerDistance = searchTriggerDistance)
        when (initialRenderState) {
            InitialListRenderState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            InitialListRenderState.Empty -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, contentPullOffset) }
                        .then(emptyStateGestureModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Note,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.no_results),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            InitialListRenderState.Content -> {
                if (isGridLayout) {
                    NoteTileGridContent(
                        notes = notes,
                        allNotes = allNotes,
                        selectedNoteIds = selectedNoteIds,
                        state = gridState,
                        onNoteClick = onNoteClick,
                        onNoteLongClick = onNoteLongClick,
                        onUpdateSortOrders = onUpdateSortOrders,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, contentPullOffset) }
                            .then(pullAction.gestureModifier)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, contentPullOffset) }
                            .then(pullAction.gestureModifier),
                        state = listState
                    ) {
                        items(notes, key = { it.id }) { note ->
                            ExpressiveNoteCard(
                                note = note,
                                isSelected = selectedNoteIds.contains(note.id),
                                isGridMode = false,
                                onClick = { onNoteClick(note.id) },
                                onLongClick = { onNoteLongClick(note.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}
