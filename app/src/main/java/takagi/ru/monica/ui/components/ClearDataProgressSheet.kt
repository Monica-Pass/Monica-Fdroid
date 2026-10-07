package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.ClearDataPhase
import takagi.ru.monica.data.ClearDataProgress
import takagi.ru.monica.data.ClearDataStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearDataProgressSheet(progress: ClearDataProgress, onDismiss: () -> Unit) {
    val running by rememberUpdatedState(progress.isRunning)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !running || it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!running) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag("clear_data_progress_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(when (progress.status) {
                ClearDataStatus.RUNNING -> R.string.clearing_data
                ClearDataStatus.COMPLETED -> R.string.clear_data_completed
                ClearDataStatus.FAILED -> R.string.clear_data_failed
            }), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("clear_data_status").semantics { liveRegion = LiveRegionMode.Polite })
            if (progress.isRunning) {
                Text(stringResource(when (progress.phase) {
                    ClearDataPhase.PREPARING -> R.string.clear_data_preparing
                    ClearDataPhase.PASSWORDS -> R.string.clear_data_passwords
                    ClearDataPhase.TOTP -> R.string.clear_data_totp
                    ClearDataPhase.NOTES -> R.string.clear_data_notes
                    ClearDataPhase.DOCUMENTS -> R.string.clear_data_documents
                    ClearDataPhase.BANK_CARDS -> R.string.clear_data_bank_cards
                    ClearDataPhase.GENERATOR_HISTORY -> R.string.clear_data_generator_history
                }), modifier = Modifier.testTag("clear_data_phase"))
            }
            val total = progress.totalEntries
            if (progress.isRunning && (total == null || total == 0 || progress.phase == ClearDataPhase.GENERATOR_HISTORY)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("clear_data_progress"))
            } else if (total != null) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 1f else (progress.clearedEntries.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("clear_data_progress"),
                )
            }
            if (total != null) Text(
                stringResource(R.string.clear_data_count, progress.clearedEntries, total),
                modifier = Modifier.testTag("clear_data_count"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(when (progress.status) {
                ClearDataStatus.RUNNING -> R.string.clear_data_wait
                ClearDataStatus.COMPLETED -> R.string.clear_data_completed_hint
                ClearDataStatus.FAILED -> R.string.clear_data_failed_hint
            }), style = MaterialTheme.typography.bodyMedium,
                color = if (progress.status == ClearDataStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!progress.isRunning) TextButton(onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().testTag("clear_data_done")) {
                Text(stringResource(R.string.ok))
            }
        }
    }
}
