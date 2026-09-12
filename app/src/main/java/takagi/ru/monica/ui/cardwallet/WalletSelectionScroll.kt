package takagi.ru.monica.ui.cardwallet

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private data class WalletSelectionScrollAnchor(val cardId: Long, val offset: Int)

/** Capture the pressed card before expansion, then keep it at the same viewport offset. */
@Composable
internal fun rememberWalletSelectionScrollAnchor(
    listState: LazyListState,
    projection: WalletStackProjection,
    selectionMode: Boolean
): (String, Long) -> Unit {
    var pendingAnchor by remember { mutableStateOf<WalletSelectionScrollAnchor?>(null) }
    val anchor = pendingAnchor

    SideEffect {
        if (!selectionMode) {
            pendingAnchor = null
        } else if (anchor != null && projection.selectionMode) {
            // The projection is asynchronous. Apply the new index with the new rows,
            // before measurement can retain an index from the collapsed list.
            val index = projection.entries.indexOfFirst {
                it is WalletStackListEntry.Single && it.card.id == anchor.cardId
            }
            if (index >= 0) listState.requestScrollToItem(index, -anchor.offset)
            pendingAnchor = null
        }
    }

    return remember(listState) {
        { sourceKey: String, cardId: Long ->
            val layoutInfo = listState.layoutInfo
            pendingAnchor = layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == sourceKey }
                // A shorter selected row must not disappear above a partly clipped stack.
                ?.let { WalletSelectionScrollAnchor(cardId, maxOf(it.offset, layoutInfo.viewportStartOffset)) }
        }
    }
}
