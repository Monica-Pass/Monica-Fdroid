package takagi.ru.monica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.repository.PasswordPageAggregateStackRepository
import takagi.ru.monica.ui.password.passwordIdFromSelectionKey
import takagi.ru.monica.viewmodel.PasswordViewModel

@Composable
internal fun PasswordStackModeDialog(
    count: Int,
    mode: ManualStackDialogMode,
    onModeChange: (ManualStackDialogMode) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_stack_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.batch_stack_confirm_message, count))
                ManualStackDialogMode.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { onModeChange(option) }, verticalAlignment = Alignment.Top) {
                        RadioButton(mode == option, onClick = { onModeChange(option) })
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(stringResource(option.titleRes), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(option.descRes), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shared manual stack membership contains identities only, never credential values. */
internal suspend fun applySharedPasswordStackMode(
    mode: ManualStackDialogMode,
    itemKeys: List<String>,
    repository: PasswordPageAggregateStackRepository,
    passwordViewModel: PasswordViewModel? = null,
): Int {
    val ids = itemKeys.mapNotNull(::passwordIdFromSelectionKey)
    if (ids.size != itemKeys.size || ids.size < 2) return 0
    val storedPasswords = ids.filter { it > 0 }
    if (storedPasswords.isNotEmpty()) checkNotNull(passwordViewModel).applyManualStackMode(storedPasswords,
        if (mode == ManualStackDialogMode.NEVER_STACK) PasswordViewModel.ManualStackMode.NEVER_STACK
        else PasswordViewModel.ManualStackMode.AUTO_STACK)
    return when (mode) {
        ManualStackDialogMode.STACK -> repository.applyManualStack(itemKeys)
        ManualStackDialogMode.AUTO_STACK -> repository.clearManualStack(itemKeys)
        ManualStackDialogMode.NEVER_STACK -> repository.markNeverStack(itemKeys)
    }
}
