package takagi.ru.monica.ui.common.pull

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.syncForUserVisibleRequest
import takagi.ru.monica.ui.haptic.rememberHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

@Stable
data class PullActionStateHandle(
    val currentOffset: Float,
    val isSettlingBack: Boolean,
    val syncHintArmed: Boolean,
    val isBitwardenSyncing: Boolean,
    val showSyncFeedback: Boolean,
    val syncFeedbackMessage: String,
    val syncFeedbackIsSuccess: Boolean,
    val nestedScrollConnection: NestedScrollConnection,
    val gestureModifier: Modifier,
    val onVerticalDrag: (Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit
)

@Composable
fun rememberPullActionState(
    isBitwardenDatabaseView: Boolean,
    isSearchExpanded: Boolean,
    searchTriggerDistance: Float,
    syncTriggerDistance: Float,
    maxDragDistance: Float,
    bitwardenRepository: BitwardenRepository,
    bitwardenVaultId: Long? = null,
    onSearchTriggered: () -> Unit
): PullActionStateHandle {
    if (!isBitwardenDatabaseView) {
        val search = rememberPullToSearchState(
            isSearchExpanded = isSearchExpanded,
            searchTriggerDistance = searchTriggerDistance,
            maxDragDistance = maxDragDistance,
            onSearchTriggered = onSearchTriggered,
        )
        return PullActionStateHandle(
            currentOffset = search.currentOffset,
            isSettlingBack = false,
            syncHintArmed = false,
            isBitwardenSyncing = false,
            showSyncFeedback = false,
            syncFeedbackMessage = "",
            syncFeedbackIsSuccess = false,
            nestedScrollConnection = search.nestedScrollConnection,
            gestureModifier = search.gestureModifier,
            onVerticalDrag = search.onVerticalDrag,
            onDragEnd = search.onDragEnd,
            onDragCancel = search.onDragCancel,
        )
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onSearchTriggeredState by rememberUpdatedState(onSearchTriggered)
    val syncHoldMillis = 500L

    var currentOffset by remember { mutableFloatStateOf(0f) }
    val searchExpandedState by rememberUpdatedState(isSearchExpanded)
    val selectedVaultId by rememberUpdatedState(bitwardenVaultId)
    var isSettlingBack by remember { mutableStateOf(false) }
    var hasSyncStageVibrated by remember { mutableStateOf(false) }
    var syncHintArmed by remember { mutableStateOf(false) }
    var isBitwardenSyncing by remember { mutableStateOf(false) }
    var lockPullUntilSyncFinished by remember { mutableStateOf(false) }
    var canRunBitwardenSync by remember { mutableStateOf(false) }
    var showSyncFeedback by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf("") }
    var syncFeedbackIsSuccess by remember { mutableStateOf(false) }
    val collapseAnimatable = remember { Animatable(0f) }

    val haptic = rememberHapticFeedback()
    val gesture = remember {
        PullSearchGestureState(
            canTriggerSearch = { !searchExpandedState && !lockPullUntilSyncFinished },
            onSearchThresholdReached = { haptic.performPullThreshold() },
            onSearchTriggered = { onSearchTriggeredState() },
        )
    }

    fun updateSearchThreshold() {
        gesture.updatePull(currentOffset >= searchTriggerDistance)
    }

    suspend fun resolveSyncableVaultId(): Long? {
        val vaultId = selectedVaultId ?: bitwardenRepository.getActiveVault()?.id ?: run {
            canRunBitwardenSync = false
            return null
        }
        val unlocked = bitwardenRepository.isVaultUnlocked(vaultId)
        canRunBitwardenSync = unlocked
        return if (unlocked) vaultId else null
    }

    fun vibratePullThreshold(isSyncStage: Boolean) {
        haptic.performPullThreshold(isSyncStage)
    }

    fun updatePullThresholdHaptics(oldOffset: Float, newOffset: Float) {
        updateSearchThreshold()

        if (!isBitwardenDatabaseView) {
            hasSyncStageVibrated = false
            return
        }

        if (oldOffset < syncTriggerDistance && newOffset >= syncTriggerDistance && !hasSyncStageVibrated) {
            hasSyncStageVibrated = true
            vibratePullThreshold(isSyncStage = true)
        } else if (newOffset < syncTriggerDistance) {
            hasSyncStageVibrated = false
        }
    }

    fun interruptCollapseAnimation() {
        if (!collapseAnimatable.isRunning && !isSettlingBack) return
        isSettlingBack = false
        scope.launch {
            collapseAnimatable.stop()
            collapseAnimatable.snapTo(currentOffset)
        }
    }

    suspend fun collapsePullOffsetSmoothly() {
        if (currentOffset <= 0.5f) {
            currentOffset = 0f
            isSettlingBack = false
            return
        }
        if (collapseAnimatable.isRunning) return
        isSettlingBack = true
        collapseAnimatable.snapTo(currentOffset)
        try {
            collapseAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 140,
                    easing = androidx.compose.animation.core.FastOutLinearInEasing
                )
            ) {
                currentOffset = value
            }
        } finally {
            currentOffset = 0f
            collapseAnimatable.snapTo(0f)
            isSettlingBack = false
        }
    }

    fun onPullRelease(): Boolean {
        // Search opens on release so pulling farther can still arm Bitwarden sync.
        if (gesture.canRelease && !searchExpandedState &&
            currentOffset >= syncTriggerDistance && syncHintArmed && !isBitwardenSyncing
        ) {
            gesture.cancelGesture()
            syncHintArmed = false
            isBitwardenSyncing = true
            lockPullUntilSyncFinished = true
            currentOffset = syncTriggerDistance
            scope.launch {
                val vaultId = resolveSyncableVaultId()
                if (vaultId == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.pull_sync_requires_bitwarden_login),
                        Toast.LENGTH_SHORT
                    ).show()
                    isBitwardenSyncing = false
                    lockPullUntilSyncFinished = false

                    hasSyncStageVibrated = false
                    collapsePullOffsetSmoothly()
                    return@launch
                }

                val syncResult = bitwardenRepository.syncForUserVisibleRequest(
                    vaultId = vaultId,
                    requestIdPrefix = "bw-pull-vault"
                )
                when (syncResult) {
                    is BitwardenRepository.SyncResult.Success -> {
                        syncFeedbackIsSuccess = true
                        syncFeedbackMessage = context.getString(R.string.pull_sync_success)
                        showSyncFeedback = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.pull_sync_success),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.Error -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.reason,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                isBitwardenSyncing = false
                lockPullUntilSyncFinished = false

                hasSyncStageVibrated = false
                collapsePullOffsetSmoothly()
                delay(1400)
                showSyncFeedback = false
            }
            return true
        }

        gesture.releaseSearch()
        return false
    }

    fun onVerticalDrag(dragAmount: Float) {
        if (lockPullUntilSyncFinished || searchExpandedState) return
        if (!gesture.isGestureActive) gesture.beginGesture()
        if (!gesture.canPull) return
        if (dragAmount < 0f) {
            currentOffset = (currentOffset + dragAmount).coerceAtLeast(0f)
            updateSearchThreshold()
            return
        }
        interruptCollapseAnimation()
        val newOffset = calculateSearchPullOffset(
            currentOffset = currentOffset,
            dragDelta = dragAmount,
            maxDragDistance = maxDragDistance
        )
        val oldOffset = currentOffset
        currentOffset = newOffset
        updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
    }

    val onDragEnd: () -> Unit = {
        val syncStarted = onPullRelease()
        scope.launch {
            if (!syncStarted && !lockPullUntilSyncFinished) {
                collapsePullOffsetSmoothly()
            }
        }
    }

    val onDragCancel: () -> Unit = {
        gesture.cancelGesture()
        syncHintArmed = false
        if (!lockPullUntilSyncFinished) {
            scope.launch { collapsePullOffsetSmoothly() }
        }
    }

    LaunchedEffect(isBitwardenDatabaseView) {
        if (isBitwardenDatabaseView) {
            resolveSyncableVaultId()
        } else {
            interruptCollapseAnimation()
            canRunBitwardenSync = false
            syncHintArmed = false
            isBitwardenSyncing = false
            lockPullUntilSyncFinished = false
            showSyncFeedback = false
            currentOffset = 0f

            hasSyncStageVibrated = false
        }
    }

    LaunchedEffect(currentOffset >= syncTriggerDistance, isBitwardenDatabaseView, isBitwardenSyncing) {
        if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && !isBitwardenSyncing) {
            resolveSyncableVaultId()
        }
    }

    LaunchedEffect(currentOffset, isBitwardenDatabaseView, canRunBitwardenSync, isBitwardenSyncing) {
        if (gesture.canPull && !isSearchExpanded && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
            delay(syncHoldMillis)
            if (gesture.canPull && !searchExpandedState && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
                syncHintArmed = true
            }
        } else {
            syncHintArmed = false
        }
    }

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            gesture.cancelGesture()

            hasSyncStageVibrated = false
            syncHintArmed = false
            if (!lockPullUntilSyncFinished && currentOffset > 0.5f) {
                collapsePullOffsetSmoothly()
            } else {
                interruptCollapseAnimation()
                currentOffset = 0f
                isSettlingBack = false
            }
        }
    }

    val nestedScrollConnection = remember(searchTriggerDistance, syncTriggerDistance, maxDragDistance) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) {
                    return available
                }
                if (currentOffset > 0 && available.y < 0) {
                    interruptCollapseAnimation()
                    val newOffset = (currentOffset + available.y).coerceAtLeast(0f)
                    val consumed = currentOffset - newOffset
                    currentOffset = newOffset
                    updateSearchThreshold()
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) {
                    return available
                }
                if (source != NestedScrollSource.UserInput || searchExpandedState) return Offset.Zero
                gesture.onScroll(contentConsumed = consumed.y != 0f)
                if (available.y > 0 && gesture.canPull) {
                    interruptCollapseAnimation()
                    val newOffset = calculateSearchPullOffset(
                        currentOffset = currentOffset,
                        dragDelta = available.y,
                        maxDragDistance = maxDragDistance
                    )
                    val oldOffset = currentOffset
                    currentOffset = newOffset
                    updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val syncStarted = onPullRelease()
                if (!syncStarted && !lockPullUntilSyncFinished) {
                    collapsePullOffsetSmoothly()
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!gesture.isGestureActive && !lockPullUntilSyncFinished && currentOffset > 0f) {
                    val syncStarted = onPullRelease()
                    if (!syncStarted && !lockPullUntilSyncFinished) {
                        collapsePullOffsetSmoothly()
                    }
                }
                return Velocity.Zero
            }
        }
    }

    return PullActionStateHandle(
        currentOffset = currentOffset,
        isSettlingBack = isSettlingBack,
        syncHintArmed = syncHintArmed,
        isBitwardenSyncing = isBitwardenSyncing,
        showSyncFeedback = showSyncFeedback,
        syncFeedbackMessage = syncFeedbackMessage,
        syncFeedbackIsSuccess = syncFeedbackIsSuccess,
        nestedScrollConnection = nestedScrollConnection,
        gestureModifier = Modifier.observePullSearchGesture(gesture).nestedScroll(nestedScrollConnection),
        onVerticalDrag = { dragAmount -> onVerticalDrag(dragAmount) },
        onDragEnd = onDragEnd,
        onDragCancel = onDragCancel
    )
}
