package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.repository.MdbxSnapshotSummary

/** Only metadata belongs here. Payloads must use the protected disclosure UI. */
@Composable
internal fun MdbxTechnicalParameters(parameters: List<Pair<String, String>>) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(parameters.joinToString("\n") { (key, value) -> "$key: $value" }))
        }) { Text(stringResource(R.string.mdbx_copy_parameters)) }
        parameters.forEach { (label, value) ->
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun MdbxSnapshotTechnicalParameters(snapshot: MdbxSnapshotSummary) {
    Text(stringResource(R.string.mdbx_snapshot_retention_help), style = MaterialTheme.typography.bodyMedium)
    MdbxTechnicalParameters(listOf(
        "snapshotId" to snapshot.snapshotId,
        "baseCommitId" to snapshot.baseCommitId,
        "name" to snapshot.name,
        "snapshotType" to snapshot.snapshotType,
        "isFull" to snapshot.isFull.toString(),
        "payloadBytes" to snapshot.payloadBytes.toString(),
        "createdAt" to snapshot.createdAt,
        "createdByDeviceId" to snapshot.createdByDeviceId,
        "autoPrune" to snapshot.autoPrune.toString(),
        "integrityOk" to snapshot.integrityOk.toString()
    ))
}
