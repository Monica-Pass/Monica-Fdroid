package takagi.ru.monica.ui.cardwallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.WalletStack

@Composable
internal fun WalletStackCreateDialog(
    selectedCount: Int,
    stacks: List<WalletStack>,
    cardsById: Map<Long, WalletListItem>,
    saving: Boolean,
    onCreate: () -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultName = stringResource(R.string.wallet_stack_default_name)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (selectedCount >= 2) R.string.wallet_stack_create else R.string.wallet_stack_add_to_existing)) },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.wallet_stack_selected_count, selectedCount))
                if (stacks.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.wallet_stack_add_to_existing), style = MaterialTheme.typography.labelLarge)
                    stacks.forEach { stack ->
                        val cover = cardsById[stack.coverId] ?: stack.memberIds.firstNotNullOfOrNull(cardsById::get)
                        TextButton(onClick = { onAdd(stack.id) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(cover?.item?.title ?: defaultName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.wallet_stack_count, stack.memberIds.size),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCreate,
                enabled = !saving && selectedCount >= 2,
                modifier = Modifier.testTag("wallet_stack_create_confirm")
            ) { Text(stringResource(R.string.wallet_stack_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun WalletStackManageDialog(
    stack: WalletStack,
    cardsById: Map<Long, WalletListItem>,
    saving: Boolean,
    onSave: (List<Long>) -> Unit,
    onDissolve: () -> Unit,
    onDismiss: () -> Unit
) {
    var memberIds by rememberSaveable(stack.id) { mutableStateOf(stack.memberIds) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.wallet_stack_manage)) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.wallet_stack_manage_hint), style = MaterialTheme.typography.bodySmall)
                memberIds.forEachIndexed { index, id ->
                    val card = cardsById[id]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            card?.item?.title ?: stringResource(R.string.wallet_stack_unavailable_card),
                            Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            memberIds = memberIds.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }, enabled = !saving && index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up))
                        }
                        IconButton(onClick = {
                            memberIds = memberIds.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }, enabled = !saving && index < memberIds.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down))
                        }
                        IconButton(onClick = { memberIds = memberIds - id }, enabled = !saving) {
                            Icon(Icons.Default.Close, stringResource(R.string.wallet_stack_remove_card))
                        }
                    }
                }
                if (memberIds.size < 2) {
                    Text(stringResource(R.string.wallet_stack_single_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                TextButton(onClick = onDissolve, enabled = !saving, modifier = Modifier.testTag("wallet_stack_dissolve")) {
                    Text(stringResource(R.string.wallet_stack_dissolve))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(memberIds) }, enabled = !saving) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } }
    )
}
