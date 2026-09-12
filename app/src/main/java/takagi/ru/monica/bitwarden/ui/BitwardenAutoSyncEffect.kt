package takagi.ru.monica.bitwarden.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.ui.theme.LocalPowerSavePolicy

@Composable
internal fun BitwardenAutoSyncEffect(
    viewModel: BitwardenViewModel?,
    selectedVaultId: Long?,
    isAllView: Boolean,
    enabled: Boolean = true
) {
    val powerSavePolicy = LocalPowerSavePolicy.current
    DisposableEffect(viewModel, selectedVaultId, isAllView, enabled, powerSavePolicy.allowBackgroundPrewarm) {
        val sessionId = when {
            !enabled || !powerSavePolicy.allowBackgroundPrewarm -> null
            isAllView -> viewModel?.beginAllViewAutoSync()
            selectedVaultId != null -> viewModel?.beginPageEnterAutoSync(selectedVaultId)
            else -> null
        }
        onDispose {
            if (sessionId != null) {
                viewModel?.endPageAutoSync(sessionId)
            }
        }
    }
}
