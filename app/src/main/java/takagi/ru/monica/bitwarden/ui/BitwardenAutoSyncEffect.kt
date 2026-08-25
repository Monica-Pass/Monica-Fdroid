package takagi.ru.monica.bitwarden.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel

private const val PAGE_ENTER_AUTO_SYNC_DELAY_MS = 1_200L

@Composable
internal fun BitwardenAutoSyncEffect(
    viewModel: BitwardenViewModel?,
    selectedVaultId: Long?,
    isAllView: Boolean,
    enabled: Boolean = true
) {
    DisposableEffect(viewModel, isAllView, enabled) {
        val allViewSessionId = if (enabled && isAllView) {
            viewModel?.beginAllViewAutoSync()
        } else {
            null
        }
        onDispose {
            if (allViewSessionId != null) {
                viewModel?.endAllViewAutoSync(allViewSessionId)
            }
        }
    }

    LaunchedEffect(viewModel, selectedVaultId, isAllView, enabled) {
        val targetViewModel = viewModel ?: return@LaunchedEffect
        val targetVaultId = selectedVaultId ?: return@LaunchedEffect
        if (!enabled || isAllView) return@LaunchedEffect
        delay(PAGE_ENTER_AUTO_SYNC_DELAY_MS)
        targetViewModel.requestPageEnterAutoSync(targetVaultId)
    }
}
