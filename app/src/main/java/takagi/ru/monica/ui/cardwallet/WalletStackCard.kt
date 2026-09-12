package takagi.ru.monica.ui.cardwallet

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import takagi.ru.monica.R
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.ui.components.BankCardShape

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WalletStackCard(
    entry: WalletStackListEntry.Stack,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManage: () -> Unit,
    onCoverBounds: (Rect) -> Unit,
    coverVisible: Boolean = true,
    controlsVisible: Boolean = coverVisible,
    modifier: Modifier = Modifier
) {
    // Keep controls at their list size; reveal them after the moving card has returned.
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(120),
        label = "wallet_stack_controls_alpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("wallet_stack_${entry.stack.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO)
                    .testTag("wallet_stack_cover")
                    // Keep the full card rectangle even when the list clips an edge.
                    .onGloballyPositioned { onCoverBounds(Rect(it.positionInWindow(), it.size.toSize())) }
                    .graphicsLayer { alpha = if (coverVisible) 1f else 0f }
            ) {
                WalletStackBackplates((entry.cards.size - 1).coerceAtMost(3), Modifier.fillMaxSize())
                WalletStackFace(entry.cover, Modifier.fillMaxSize())
                WalletStackControls(entry.cards.size, onManage,
                    enabled = controlsVisible,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .graphicsLayer { alpha = controlsAlpha })
            }
        }
    }
}

@Composable
internal fun WalletStackControls(
    cardCount: Int,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.testTag("wallet_stack_controls"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Layers, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.wallet_stack_count, cardCount), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onManage, enabled = enabled, modifier = Modifier.testTag("wallet_stack_manage")) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.wallet_stack_manage))
            }
        }
    }
}

/** Decorative surfaces only: a closed stack never loads the other members' artwork. */
@Composable
internal fun WalletStackBackplates(layerCount: Int, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    val edge = MaterialTheme.colorScheme.outlineVariant
    Box(modifier) {
        for (layer in layerCount.coerceIn(0, 3) downTo 1) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1f - layer * 0.035f
                        translationY = (layer * 4).dp.toPx()
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(BankCardShape)
                    .background(androidx.compose.ui.graphics.lerp(color, edge, layer * 0.14f))
            )
        }
    }
}

@Composable
internal fun WalletStackFace(card: WalletListItem, modifier: Modifier = Modifier) {
    val config = when (card.type) {
        WalletListItemType.BANK_CARD -> card.bankCardData?.cardFace
        WalletListItemType.DOCUMENT -> card.documentData?.cardFace
        WalletListItemType.BILLING_ADDRESS -> card.billingAddressData?.cardFace
    }
    val preview = when (card.type) {
        WalletListItemType.BANK_CARD -> card.bankCardData?.let { bankCardFacePreviewData(card.item.title, it) }
        WalletListItemType.DOCUMENT -> card.documentData?.let { documentCardFacePreviewData(card.item.title, it) }
        WalletListItemType.BILLING_ADDRESS -> card.billingAddressData?.let { billingAddressCardFacePreviewData(card.item.title, it) }
    } ?: CardFacePreviewData(title = card.item.title)
    val bitmap = config?.let { rememberCardFaceBitmap(card.item, it.imageAttachmentName, maxDimension = 640) }
    CardFaceArtwork(
        previewData = preview,
        bitmap = bitmap,
        displayMode = config?.displayMode ?: CardFaceDisplayMode.ALL,
        showBrandIcon = config?.showBrandIcon ?: true,
        modifier = modifier.shadow(2.dp, BankCardShape)
    )
}
