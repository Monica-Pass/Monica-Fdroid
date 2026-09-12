package takagi.ru.monica.ui.cardwallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

internal data class WalletStackPreview(
    val entry: WalletStackListEntry.Stack,
    val originBounds: Rect?
)

private class WalletStackPreviewViewModel : ViewModel() {
    var preview: WalletStackPreview? = null

    override fun onCleared() {
        preview = null
    }
}

/** Keep only the open stack in its navigation entry while the live wallet reloads on return. */
@Composable
internal fun rememberWalletStackPreview(
    stackId: String?,
    entry: WalletStackListEntry.Stack?,
    originBounds: Rect?,
    isReady: Boolean
): WalletStackPreview? {
    val cache = viewModel { WalletStackPreviewViewModel() }
    val previous = cache.preview?.takeIf { it.entry.stack.id == stackId }
    val resolvedEntry = if (isReady) entry else previous?.entry ?: entry
    val resolvedBounds = originBounds ?: previous?.originBounds
    val preview = remember(resolvedEntry, resolvedBounds) {
        resolvedEntry?.let { WalletStackPreview(it, resolvedBounds) }
    }
    // A ready source is authoritative: removed stacks and closed browsers drop their cache.
    SideEffect { cache.preview = preview }
    return preview
}
