package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupReport
import takagi.ru.monica.data.FailedItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupProblemDialog(
    report: BackupReport,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSkipPasskeys: (List<FailedItem>) -> Unit,
    partialSaved: Boolean = false,
) {
    val context = LocalContext.current
    val items = if (partialSaved) report.skippedItems else report.failedItems
    val skippable = report.failedItems.filter { it.type == context.getString(R.string.backup_content_passkeys) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                .padding(20.dp).testTag("backup_problem_dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (partialSaved) R.string.passkey_partial_saved else R.string.passkey_backup_problem_title),
                    style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (partialSaved) R.string.passkey_partial_saved_detail else R.string.passkey_backup_problem_detail),
                        style = MaterialTheme.typography.bodyMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items.forEachIndexed { index, item ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 4.dp,
                                    topEnd = if (index == 0) 20.dp else 4.dp,
                                    bottomStart = if (index == items.lastIndex) 20.dp else 4.dp,
                                    bottomEnd = if (index == items.lastIndex) 20.dp else 4.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(item.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(item.reason, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (item.reason == context.getString(R.string.backup_passkey_device_bound)) {
                                        Text(stringResource(R.string.passkey_device_bound_solution), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    if (!partialSaved && skippable.isNotEmpty()) {
                        Text(stringResource(R.string.passkey_skip_explanation), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!partialSaved && skippable.isNotEmpty()) {
                    FilledTonalButton(onClick = { onSkipPasskeys((report.skippedItems + skippable).distinctBy { it.id }) },
                        modifier = Modifier.fillMaxWidth().testTag("backup_skip_passkeys")) {
                        Text(stringResource(R.string.passkey_skip_and_backup, skippable.size))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!partialSaved) TextButton(onClick = onRetry, modifier = Modifier.testTag("backup_retry")) {
                        Text(stringResource(R.string.passkey_retry_full_backup))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("backup_problem_close")) {
                        Text(stringResource(R.string.passkey_problem_close))
                    }
                }
            }
        }
    }
}
