package takagi.ru.monica.credentialexchange

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.animateMonicaContentSize

/** Shared import/export controls, based on the editable M3E Canvas transfer design. */
@Composable
fun TransferChoiceRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    index: Int = 0,
    count: Int = 1,
) {
    val shape = RoundedCornerShape(
        topStart = if (index == 0) 24.dp else 6.dp,
        topEnd = if (index == 0) 24.dp else 6.dp,
        bottomStart = if (index == count - 1) 24.dp else 6.dp,
        bottomEnd = if (index == count - 1) 24.dp else 6.dp,
    )
    Surface(
        onClick = onClick, enabled = enabled, shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferDestinationField(
    destination: ImportDestination,
    onSelect: (ImportDestination) -> Unit,
    enabled: Boolean = true,
    forExport: Boolean = false,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val options by produceState<List<ImportDestinationOption>?>(null, context, show, forExport) {
        value = ImportDestinationWriter.destinations(context, forExport)
    }
    val current = options?.firstOrNull { it.destination == destination }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (forExport) R.string.exchange_source else R.string.exchange_save_to),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp))
        TransferChoiceRow(
            title = current?.title ?: if (destination == ImportDestination.Local) "Monica" else stringResource(R.string.exchange_destination_unavailable),
            subtitle = current?.subtitle.orEmpty(), icon = Icons.Default.Storage,
            enabled = enabled, onClick = { show = true },
        )
    }
    if (show) ModalBottomSheet(onDismissRequest = { show = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(if (forExport) R.string.exchange_source else R.string.exchange_save_to),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(options.orEmpty(), key = { _, it -> it.destination.key }) { index, option ->
                TransferChoiceRow(option.title, option.subtitle, Icons.Default.Storage,
                    enabled = option.available, selected = destination == option.destination,
                    index = index, count = options.orEmpty().size,
                    onClick = { onSelect(option.destination); show = false })
            }
        }
    }
}

@Composable
fun TransferSummary(summary: ImportResultSummary) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateMonicaContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (summary.failed == 0) Icons.Default.CheckCircle else Icons.Default.Info, null)
            Text(stringResource(R.string.exchange_import_done), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.exchange_result, summary.imported, summary.skipped, summary.failed))
            if (summary.failed > 0) Text(stringResource(R.string.exchange_import_partial), style = MaterialTheme.typography.bodySmall)
            if (summary.queuedToBitwarden) Text(stringResource(R.string.exchange_pending), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TransferCredentialCounts(passwords: Int, passkeys: Int, skipped: Int, exporting: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.exchange_password_count, passwords), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.exchange_passkey_count, passkeys), style = MaterialTheme.typography.titleMedium)
        if (skipped > 0) Text(
            stringResource(if (exporting) R.string.exchange_export_skipped else R.string.exchange_skipped_count, skipped),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
