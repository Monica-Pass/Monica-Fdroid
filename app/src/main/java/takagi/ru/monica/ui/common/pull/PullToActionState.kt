package takagi.ru.monica.ui.common.pull

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

@Stable
data class PullToActionStateHandle(
    val currentOffset: Float,
    val isArmed: Boolean,
    val nestedScrollConnection: NestedScrollConnection,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit
)

/**
 * Generic top-edge pull state for deliberate actions such as navigation.
 *
 * The scroll child keeps priority. A pull starts only after the child reports
 * unconsumed downward motion and [canStartPull] confirms that the page is at
 * its own top boundary.
 */
@Composable
fun rememberPullToActionState(
    enabled: Boolean,
    triggerDistance: Float,
    maxDragDistance: Float,
    onTriggered: () -> Unit,
    canStartPull: () -> Boolean = { true }
): PullToActionStateHandle {
    val scope = rememberCoroutineScope()
    val haptic = rememberHapticFeedback()
    val enabledState by rememberUpdatedState(enabled)
    val canStartPullState by rememberUpdatedState(canStartPull)
    val onTriggeredState by rememberUpdatedState(onTriggered)

    var currentOffset by remember { mutableFloatStateOf(0f) }
    var thresholdFeedbackSent by remember { mutableStateOf(false) }
    var actionTriggeredForPull by remember { mutableStateOf(false) }
    val settleAnimatable = remember { Animatable(0f) }

    fun updateOffset(newOffset: Float) {
        val clampedOffset = newOffset.coerceIn(0f, maxDragDistance.coerceAtLeast(0f))
        val oldOffset = currentOffset
        currentOffset = clampedOffset

        if (
            oldOffset < triggerDistance &&
            clampedOffset >= triggerDistance &&
            !thresholdFeedbackSent
        ) {
            thresholdFeedbackSent = true
            haptic.performPullThreshold()
        } else if (clampedOffset < triggerDistance) {
            thresholdFeedbackSent = false
        }
    }

    fun interruptSettleAnimation() {
        if (!settleAnimatable.isRunning) return
        scope.launch {
            settleAnimatable.stop()
            settleAnimatable.snapTo(currentOffset)
        }
    }

    suspend fun settleBack() {
        if (currentOffset <= 0.5f) {
            currentOffset = 0f
            thresholdFeedbackSent = false
            actionTriggeredForPull = false
            return
        }
        if (settleAnimatable.isRunning) return

        settleAnimatable.snapTo(currentOffset)
        try {
            settleAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) {
                currentOffset = value.coerceAtLeast(0f)
            }
        } finally {
            currentOffset = 0f
            settleAnimatable.snapTo(0f)
            thresholdFeedbackSent = false
            actionTriggeredForPull = false
        }
    }

    fun triggerIfReady() {
        if (
            enabledState &&
            !actionTriggeredForPull &&
            currentOffset >= triggerDistance
        ) {
            actionTriggeredForPull = true
            onTriggeredState()
        }
    }

    fun finishPull(triggerAction: Boolean) {
        if (currentOffset <= 0f) return
        scope.launch {
            if (triggerAction) {
                triggerIfReady()
            }
            settleBack()
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled && currentOffset > 0f) {
            settleBack()
        }
    }

    val nestedScrollConnection = remember(triggerDistance, maxDragDistance) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (currentOffset > 0f && available.y < 0f) {
                    interruptSettleAnimation()
                    val newOffset = (currentOffset + available.y).coerceAtLeast(0f)
                    val consumed = currentOffset - newOffset
                    updateOffset(newOffset)
                    return Offset(x = 0f, y = -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (
                    enabledState &&
                    canStartPullState() &&
                    available.y > 0f &&
                    source == NestedScrollSource.UserInput
                ) {
                    interruptSettleAnimation()
                    updateOffset(
                        calculateDampedPullOffset(
                            currentOffset = currentOffset,
                            dragDelta = available.y,
                            maxDragDistance = maxDragDistance
                        )
                    )
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (currentOffset <= 0f) return Velocity.Zero
                triggerIfReady()
                settleBack()
                return available
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                if (currentOffset > 0f) {
                    triggerIfReady()
                    settleBack()
                }
                return Velocity.Zero
            }
        }
    }

    return PullToActionStateHandle(
        currentOffset = currentOffset,
        isArmed = currentOffset >= triggerDistance,
        nestedScrollConnection = nestedScrollConnection,
        onDragEnd = { finishPull(triggerAction = true) },
        onDragCancel = { finishPull(triggerAction = false) }
    )
}
