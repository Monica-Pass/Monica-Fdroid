package takagi.ru.monica.ui.vaultv2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.VaultListSort

internal val VaultListSort.labelResource: Int get() = when (this) {
    VaultListSort.TITLE_ASC -> R.string.vault_sort_title_asc
    VaultListSort.TITLE_DESC -> R.string.vault_sort_title_desc
    VaultListSort.CREATED_DESC -> R.string.vault_sort_created_desc
    VaultListSort.CREATED_ASC -> R.string.vault_sort_created_asc
    VaultListSort.UPDATED_DESC -> R.string.vault_sort_updated_desc
    VaultListSort.UPDATED_ASC -> R.string.vault_sort_updated_asc
}

@Composable
internal fun VaultV2SortMenuItem(sort: VaultListSort, onClick: () -> Unit) {
    DropdownMenuItem(
        modifier = Modifier.testTag("vault_sort_menu"),
        text = {
            Column {
                Text(stringResource(R.string.vault_sort))
                Text(stringResource(sort.labelResource), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultV2SortSheet(sort: VaultListSort, onSelect: (VaultListSort) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 24.dp).testTag("vault_sort_sheet")) {
            Text(stringResource(R.string.vault_sort), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 12.dp))
            Text(stringResource(R.string.vault_sort_scope), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VaultListSort.entries.forEachIndexed { index, option ->
                    val selected = option == sort
                    Surface(shape = RoundedCornerShape(
                        topStart = if (index == 0) 24.dp else 4.dp, topEnd = if (index == 0) 24.dp else 4.dp,
                        bottomStart = if (index == VaultListSort.entries.lastIndex) 24.dp else 4.dp,
                        bottomEnd = if (index == VaultListSort.entries.lastIndex) 24.dp else 4.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("vault_sort_${option.name}")
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(option.labelResource), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
