package takagi.ru.monica.ui.cardwallet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.ui.LocalAnimatedVisibilityScope
import takagi.ru.monica.ui.components.BankCardShape

/** A scene overlay keeps card scrolling independent from the list's pull/swipe actions. */
@Composable
internal fun WalletStackBrowser(
    entry: WalletStackListEntry.Stack,
    originBounds: Rect?,
    initialCardId: Long,
    animateEntrance: Boolean,
    onOpened: () -> Unit,
    onFocusedCardChanged: (Long) -> Unit,
    onCollapseStart: (Long) -> Unit,
    onRevealCover: () -> Unit,
    onDismiss: () -> Unit,
    onOpenCard: (WalletListItem) -> Unit,
    onManage: () -> Unit,
    title: String? = null,
    reduceAnimations: Boolean = false,
    sourceName: (WalletListItem) -> String? = { null },
) {
    val cards = entry.cards
    if (cards.isEmpty()) return
    val navigation = LocalAnimatedVisibilityScope.current?.transition
    // Overlay callbacks can outlive the navigation frame that created them. Read the
    // transition when an action runs instead of capturing its returning/exiting state.
    val handlesBack by remember(navigation) {
        derivedStateOf { navigation == null || navigation.targetState == EnterExitState.Visible }
    }
    val isNavigationActive by remember(navigation) {
        derivedStateOf { handlesBack && (navigation == null || navigation.currentState == EnterExitState.Visible) }
    }
    val scope = rememberCoroutineScope()
    var position by rememberSaveable(entry.stack.id) {
        mutableFloatStateOf(cards.indexOfFirst { it.id == initialCardId }.coerceAtLeast(0).toFloat())
    }
    var stepPx by remember { mutableFloatStateOf(1f) }
    var closing by remember(entry.stack.id) { mutableStateOf(false) }
    var closingIndex by remember(entry.stack.id) { mutableIntStateOf(-1) }
    val expansion = remember(entry.stack.id) { Animatable(if (animateEntrance) 0f else 1f) }
    val focusIndex by remember(cards.size) {
        derivedStateOf { position.roundToInt().coerceIn(0, cards.lastIndex) }
    }
    val visibleRange by remember(cards.size) {
        derivedStateOf {
            val center = floor(position).toInt()
            (center - 3).coerceAtLeast(0)..(center + 4).coerceAtMost(cards.lastIndex)
        }
    }
    val latestOnOpened by rememberUpdatedState(onOpened)
    val latestOnFocus by rememberUpdatedState(onFocusedCardChanged)
    val latestOnCollapse by rememberUpdatedState(onCollapseStart)
    val latestOnRevealCover by rememberUpdatedState(onRevealCover)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val scroll = rememberScrollableState { delta ->
        if (closing || !isNavigationActive) return@rememberScrollableState 0f
        val old = position
        val pullingPastEnd = old <= 0f && delta > 0f || old >= cards.lastIndex && delta < 0f
        val overpull = abs(old - old.coerceIn(0f, cards.lastIndex.toFloat()))
        val resistance = WALLET_STACK_DRAG_RESISTANCE *
            if (pullingPastEnd) 0.24f / (1f + overpull * 6f) else 1f
        position = (old - delta / stepPx * resistance).coerceIn(-0.2f, cards.lastIndex + 0.2f)
        (old - position) * stepPx / resistance
    }
    val settleSpring = remember {
        spring<Float>(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 0.0005f)
    }
    val fling = remember(cards.lastIndex) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                if (closing) return 0f
                val velocity = walletStackReleaseVelocity(initialVelocity, stepPx)
                val target = walletStackSettleTarget(position, velocity, cards.lastIndex)
                // This animation remains inside scrollable's mutation, so a new drag or
                // collapse cancels it immediately. No second idle-time snap is scheduled.
                animate(position, target, initialVelocity = velocity, animationSpec = settleSpring) { value, _ ->
                    position = value.coerceIn(-0.2f, cards.lastIndex + 0.2f)
                }
                return 0f
            }
        }
    }
    fun moveFocusBy(offset: Int): Boolean {
        if (closing || !isNavigationActive) return false
        scope.launch {
            scroll.scroll {
                val target = (focusIndex + offset).coerceIn(0, cards.lastIndex).toFloat()
                animate(position, target, animationSpec = settleSpring) { value, _ -> position = value }
            }
        }
        return true
    }
    val requestCollapse: () -> Unit = {
        if (!closing && isNavigationActive) {
            closingIndex = focusIndex
            closing = true
            latestOnCollapse(cards[focusIndex].id)
            scope.launch {
                scroll.stopScroll(MutatePriority.PreventUserInput)
                expansion.animateTo(0f, tween(if (reduceAnimations) 0 else 360, easing = FastOutSlowInEasing))
                // Paint the real cover underneath the matching final animation frame before
                // removing the overlay. This avoids an empty or stale-cover frame.
                latestOnRevealCover()
                repeat(2) { withFrameNanos { } }
                latestOnDismiss()
            }
        }
    }

    LaunchedEffect(entry.stack.id) {
        if (animateEntrance) expansion.animateTo(1f, tween(if (reduceAnimations) 0 else 420, easing = FastOutSlowInEasing))
        latestOnOpened()
    }
    val cardIds = remember(cards) { cards.map(WalletListItem::id) }
    LaunchedEffect(cardIds) {
        position = position.coerceIn(0f, cards.lastIndex.toFloat())
        snapshotFlow { focusIndex }.distinctUntilChanged().collect { latestOnFocus(cards[it].id) }
    }
    val stackName = title ?: stringResource(R.string.wallet_stack_default_name)
    val nextLabel = stringResource(R.string.wallet_stack_next)
    val previousLabel = stringResource(R.string.wallet_stack_previous)
    WalletStackOverlay {
        // Keep early return/back presses inside the stack until its page is interactive.
        BackHandler(enabled = handlesBack, onBack = requestCollapse)
        val background = MaterialTheme.colorScheme.surface
        val density = LocalDensity.current
        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        var windowOffset by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(
            Modifier.fillMaxSize().testTag("wallet_stack_browser")
                .onGloballyPositioned { windowOffset = it.positionInWindow() }
                .semantics { paneTitle = stackName }
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val cardWidth = (maxWidth - 40.dp).coerceAtMost(560.dp)
            val cardHeight = cardWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
            val cardWidthPx = with(density) { cardWidth.toPx() }
            val cardHeightPx = with(density) { cardHeight.toPx() }
            // Lay out artwork at the list cover's real size so text and shadows match
            // when the moving card returns to the list.
            val faceWidth = originBounds?.width?.takeIf { it > 0f }
                ?.let { with(density) { it.toDp() } } ?: cardWidth
            val faceHeight = faceWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
            val faceWidthPx = with(density) { faceWidth.toPx() }
            val viewportTop = safeInsets.calculateTopPadding() + 88.dp
            val viewportBottom = maxHeight - safeInsets.calculateBottomPadding() - 116.dp
            val centerY = with(density) { ((viewportTop + viewportBottom) / 2).toPx() }
            val source = originBounds?.takeIf { it.width > 0 && it.height > 0 }
                ?.translate(-windowOffset)
                ?: Rect((widthPx - cardWidthPx) / 2, centerY - cardHeightPx / 2,
                    (widthPx + cardWidthPx) / 2, centerY + cardHeightPx / 2)
            SideEffect { stepPx = cardHeightPx * 0.9f }
            val anchorIndex = if (closingIndex >= 0) closingIndex.coerceAtMost(cards.lastIndex) else focusIndex

            Box(Modifier.fillMaxSize().graphicsLayer { alpha = expansion.value }.background(background))
            Box(
                Modifier.fillMaxSize().testTag("wallet_stack_scroll")
                    .scrollable(scroll, Orientation.Vertical, flingBehavior = fling,
                        enabled = isNavigationActive && !closing && expansion.value > 0.95f)
                    .semantics {
                        verticalScrollAxisRange = ScrollAxisRange({ position }, { cards.lastIndex.toFloat() })
                        customActions = listOf(
                            CustomAccessibilityAction(nextLabel) { moveFocusBy(1) },
                            CustomAccessibilityAction(previousLabel) { moveFocusBy(-1) }
                        )
                    }
            ) {
                // Only the neighbourhood is composed; transformations read scroll state during
                // drawing, so an entire wallet or all its bitmaps are never animated per frame.
                visibleRange.forEach { index ->
                    val card = cards[index]
                    key(card.id) {
                        Box(
                            Modifier.size(faceWidth, faceHeight)
                                .zIndex(if (index == anchorIndex) 30f else 20f - abs(index - anchorIndex))
                                .graphicsLayer {
                                    val progress = expansion.value
                                    val travel = smoothStackProgress(progress / 0.65f)
                                    val spread = smoothStackProgress((progress - 0.32f) / 0.68f)
                                    val anchor = walletStackPose(anchorIndex, position, cardWidthPx, cardHeightPx, widthPx, centerY)
                                    val pose = walletStackPose(index, position, cardWidthPx, cardHeightPx, widthPx, centerY)
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    translationX = lerp(source.left, anchor.x, travel) + (pose.x - anchor.x) * spread
                                    translationY = lerp(source.top, anchor.y, travel) + (pose.y - anchor.y) * spread
                                    val scale = lerp(source.width / cardWidthPx, anchor.scale, travel) +
                                        (pose.scale - anchor.scale) * spread
                                    scaleX = scale * cardWidthPx / faceWidthPx
                                    scaleY = scale * cardWidthPx / faceWidthPx
                                    alpha = if (index == anchorIndex) 1f else spread
                                }
                                .testTag("wallet_stack_card_${card.id}")
                                .clickable(enabled = isNavigationActive && !closing && expansion.value > 0.95f) {
                                    scope.launch {
                                        scroll.stopScroll(MutatePriority.PreventUserInput)
                                        position = index.toFloat()
                                        latestOnFocus(card.id)
                                        onOpenCard(card)
                                    }
                                }
                                .semantics(mergeDescendants = true) { contentDescription = card.item.title }
                        ) {
                            if (index == anchorIndex) {
                                WalletStackBackplates(
                                    (cards.size - 1).coerceAtMost(3),
                                    Modifier.fillMaxSize().graphicsLayer {
                                        alpha = 1f - smoothStackProgress((expansion.value - 0.32f) / 0.68f)
                                    }
                                )
                            }
                            WalletStackFace(card, Modifier.fillMaxSize())
                            // Wash the artwork while keeping an opaque card surface. Fading the
                            // entire card would let text from cards behind it show through.
                            Box(
                                Modifier.fillMaxSize().clip(BankCardShape).graphicsLayer {
                                    alpha = (abs(index - position) * 0.15f).coerceIn(0f, 0.8f) *
                                        smoothStackProgress((expansion.value - 0.32f) / 0.68f)
                                }.background(background)
                            )
                        }
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().height(viewportTop + 38.dp).align(Alignment.TopCenter)
                    .graphicsLayer { alpha = smoothStackProgress((expansion.value - 0.32f) / 0.68f) }
                    .background(Brush.verticalGradient(0f to background, 0.7f to background, 1f to Color.Transparent))
            )
            Box(
                Modifier.fillMaxWidth().height(maxHeight - viewportBottom + 28.dp).align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = smoothStackProgress((expansion.value - 0.32f) / 0.68f) }
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to background, 1f to background))
            )
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(top = safeInsets.calculateTopPadding() + 12.dp, start = 24.dp, end = 16.dp)
                    .graphicsLayer { alpha = smoothStackProgress((expansion.value - 0.32f) / 0.68f) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stackName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.wallet_stack_browse_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sourceName(cards[focusIndex])?.let { source ->
                        Text(source, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { if (isNavigationActive) onManage() }, enabled = !closing) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.wallet_stack_manage))
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp)
                    .graphicsLayer { alpha = smoothStackProgress((expansion.value - 0.32f) / 0.68f) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.wallet_stack_position, focusIndex + 1, cards.size),
                    modifier = Modifier.testTag("wallet_stack_position"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = requestCollapse,
                    enabled = !closing,
                    modifier = Modifier.heightIn(min = 52.dp).testTag("wallet_stack_collapse")
                ) {
                    Icon(Icons.Default.Layers, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.wallet_stack_collapse))
                }
            }
        }
    }
}
