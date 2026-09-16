package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.keepass.KeePassConflictChangeType
import takagi.ru.monica.keepass.KeePassConflictDecision
import takagi.ru.monica.keepass.KeePassConflictDetail
import takagi.ru.monica.keepass.KeePassConflictDetailKind
import takagi.ru.monica.keepass.KeePassConflictItem
import takagi.ru.monica.keepass.KeePassConflictObjectType
import takagi.ru.monica.keepass.KeePassConflictResolutionSide
import takagi.ru.monica.keepass.KeePassConflictResolutionState
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.MonicaExpansionChevron

@Composable
internal fun KeePassConflictBanner(onReview: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onReview,
        modifier = modifier.fillMaxWidth().testTag("keepass-conflict-review"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.keepass_conflict_banner_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.keepass_conflict_banner_summary), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.keepass_conflict_review),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeePassConflictResolutionSheet(
    state: KeePassConflictResolutionState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDecision: (KeePassConflictDecision, Map<String, KeePassConflictResolutionSide>) -> Unit
) {
    val preview = state.preview
    var selections by remember(preview) {
        mutableStateOf<Map<String, KeePassConflictResolutionSide>>(emptyMap())
    }
    var otherOptionsExpanded by remember(preview) { mutableStateOf(false) }
    var replacement by remember(preview) { mutableStateOf<KeePassConflictDecision?>(null) }
    val requiredDetails = remember(preview) { preview?.snapshot?.items.orEmpty().flatMap { it.details } }
    val visibleItems = remember(preview) {
        preview?.snapshot?.items.orEmpty().sortedByDescending { it.details.isNotEmpty() }
    }
    val selectedCount = requiredDetails.count { it.id in selections }
    val resolving by rememberUpdatedState(state.resolving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { next -> !resolving || next != SheetValue.Hidden }
    )
    ModalBottomSheet(
        onDismissRequest = { if (!state.resolving) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.88f)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("keepass-conflict-list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(DatabaseManagementGroupSpacing)
            ) {
                item(key = "heading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.keepass_conflict_review), style = MaterialTheme.typography.headlineSmall)
                            Text(state.databaseName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismiss, enabled = !state.resolving) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    }
                }
                if (state.loading) {
                    item(key = "loading") {
                        Row(
                            Modifier.padding(vertical = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.keepass_conflict_comparing))
                        }
                    }
                }
                state.error?.let { error ->
                    item(key = "error") {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(error, modifier = Modifier.testTag("keepass-conflict-error"))
                                OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("keepass-conflict-refresh")) {
                                    Text(stringResource(R.string.keepass_conflict_refresh))
                                }
                            }
                        }
                    }
                }
                if (preview != null) {
                    item(key = "summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.keepass_conflict_merge_help), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    R.string.keepass_conflict_summary,
                                    preview.snapshot.localChangeCount,
                                    preview.snapshot.remoteChangeCount,
                                    preview.snapshot.ambiguousCount
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = onRefresh, enabled = !state.resolving) {
                                Text(stringResource(R.string.keepass_conflict_refresh))
                            }
                        }
                    }
                    if (visibleItems.isEmpty()) {
                        item(key = "identical") { Text(stringResource(R.string.keepass_conflict_no_changes)) }
                    }
                    itemsIndexed(visibleItems, key = { _, item -> "${item.objectType}:${item.id}" }) { index, item ->
                        ConflictItemCard(item, selections, enabled = !state.resolving, shape = settingsSectionItemShape(index, visibleItems.size)) { id, side ->
                            selections = selections + (id to side)
                        }
                    }
                    item(key = "options") {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column {
                                TextButton(
                                    onClick = { otherOptionsExpanded = !otherOptionsExpanded },
                                    enabled = !state.resolving,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.keepass_conflict_other_options), Modifier.weight(1f))
                                    MonicaExpansionChevron(otherOptionsExpanded, contentDescription = null)
                                }
                                MonicaExpandableContent(otherOptionsExpanded) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { replacement = KeePassConflictDecision.KEEP_LOCAL },
                                            enabled = !state.resolving,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(stringResource(R.string.keepass_conflict_keep_local)) }
                                        OutlinedButton(
                                            onClick = { replacement = KeePassConflictDecision.USE_REMOTE },
                                            enabled = !state.resolving,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(stringResource(R.string.keepass_conflict_use_remote)) }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "recovery") {
                        Text(
                            stringResource(R.string.keepass_conflict_recovery_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (preview != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (requiredDetails.isNotEmpty()) {
                        Text(
                            stringResource(R.string.keepass_conflict_selected_count, selectedCount, requiredDetails.size),
                            modifier = Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Button(
                        onClick = { onDecision(KeePassConflictDecision.MERGE, selections) },
                        enabled = !state.resolving && selectedCount == requiredDetails.size,
                        modifier = Modifier.fillMaxWidth().testTag("keepass-conflict-merge")
                    ) {
                        if (state.resolving) {
                            CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(stringResource(if (state.resolving) R.string.keepass_conflict_merging else R.string.keepass_conflict_merge_sync))
                    }
                }
            }
        }
    }
    replacement?.let { decision ->
        AlertDialog(
            onDismissRequest = { replacement = null },
            title = { Text(stringResource(if (decision == KeePassConflictDecision.KEEP_LOCAL) R.string.keepass_conflict_keep_local else R.string.keepass_conflict_use_remote)) },
            text = { Text(stringResource(if (decision == KeePassConflictDecision.KEEP_LOCAL) R.string.keepass_conflict_keep_local_confirm else R.string.keepass_conflict_use_remote_confirm)) },
            confirmButton = {
                TextButton(onClick = { replacement = null; onDecision(decision, emptyMap()) }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { replacement = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
internal fun ConflictItemCard(
    item: KeePassConflictItem,
    selections: Map<String, KeePassConflictResolutionSide>,
    enabled: Boolean,
    shape: Shape = DatabaseManagementPanelShape,
    onSelect: (String, KeePassConflictResolutionSide) -> Unit
) {
    var expanded by remember(item) { mutableStateOf(item.details.isNotEmpty()) }
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = !expanded },
                enabled = enabled,
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (item.objectType == KeePassConflictObjectType.ENTRY || item.objectType == KeePassConflictObjectType.GROUP) item.label else conflictObjectLabel(item.objectType),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (!item.ambiguous) stringResource(R.string.keepass_conflict_auto_merge) else conflictObjectLabel(item.objectType),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MonicaExpansionChevron(expanded, contentDescription = null)
                }
            }
            MonicaExpandableContent(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${stringResource(R.string.keepass_conflict_choose_local)}: ${conflictChangeLabel(item.localChange)} · " +
                            "${stringResource(R.string.keepass_conflict_choose_remote)}: ${conflictChangeLabel(item.remoteChange)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    item.details.forEach { detail -> ConflictDetailChoices(detail, selections[detail.id], enabled, onSelect) }
                }
            }
        }
    }
}

@Composable
private fun ConflictDetailChoices(
    detail: KeePassConflictDetail,
    selected: KeePassConflictResolutionSide?,
    enabled: Boolean,
    onSelect: (String, KeePassConflictResolutionSide) -> Unit
) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (detail.kind) {
                KeePassConflictDetailKind.FIELD -> stringResource(R.string.keepass_conflict_detail_field, conflictFieldLabel(detail.label))
                KeePassConflictDetailKind.LOCATION -> stringResource(R.string.keepass_conflict_detail_location)
                KeePassConflictDetailKind.EXISTENCE -> stringResource(R.string.keepass_conflict_detail_existence)
                KeePassConflictDetailKind.PROPERTIES -> stringResource(R.string.keepass_conflict_detail_properties)
            },
            style = MaterialTheme.typography.titleSmall
        )
        KeePassConflictResolutionSide.entries.forEachIndexed { index, side ->
            val summary = if (side == KeePassConflictResolutionSide.LOCAL) detail.localSummary else detail.remoteSummary
            Surface(
                shape = settingsSectionItemShape(index, KeePassConflictResolutionSide.entries.size),
                color = if (selected == side) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(settingsSectionItemShape(index, KeePassConflictResolutionSide.entries.size))
                        .selectable(selected == side, enabled = enabled, role = Role.RadioButton) { onSelect(detail.id, side) }
                        .testTag("keepass-conflict-choice:${detail.id}:$side")
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(selected = selected == side, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (side == KeePassConflictResolutionSide.LOCAL) R.string.keepass_conflict_choose_local else R.string.keepass_conflict_choose_remote))
                        Text(
                            when {
                                detail.protectedValue -> stringResource(R.string.keepass_conflict_protected_value)
                                detail.kind == KeePassConflictDetailKind.PROPERTIES -> stringResource(R.string.keepass_conflict_properties_value)
                                detail.kind == KeePassConflictDetailKind.EXISTENCE && summary == "Deleted" -> stringResource(R.string.keepass_conflict_change_deleted)
                                else -> summary ?: "—"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun conflictFieldLabel(name: String): String = when (name) {
    "Title" -> stringResource(R.string.title)
    "UserName" -> stringResource(R.string.username)
    "Password" -> stringResource(R.string.password)
    "Notes" -> stringResource(R.string.notes)
    else -> name
}

@Composable
private fun conflictObjectLabel(type: KeePassConflictObjectType): String = stringResource(when (type) {
    KeePassConflictObjectType.ENTRY -> R.string.keepass_conflict_kind_entry
    KeePassConflictObjectType.GROUP -> R.string.keepass_conflict_kind_group
    KeePassConflictObjectType.DATABASE_METADATA -> R.string.keepass_conflict_kind_metadata
    KeePassConflictObjectType.DELETED_OBJECT -> R.string.keepass_conflict_kind_deleted_object
    KeePassConflictObjectType.BINARY -> R.string.keepass_conflict_kind_binary
    KeePassConflictObjectType.CUSTOM_ICON -> R.string.keepass_conflict_kind_icon
})

@Composable
private fun conflictChangeLabel(type: KeePassConflictChangeType?): String = stringResource(when (type) {
    KeePassConflictChangeType.ADDED -> R.string.keepass_conflict_change_added
    KeePassConflictChangeType.MODIFIED -> R.string.keepass_conflict_change_modified
    KeePassConflictChangeType.MOVED -> R.string.keepass_conflict_change_moved
    KeePassConflictChangeType.DELETED -> R.string.keepass_conflict_change_deleted
    null -> R.string.keepass_conflict_change_unchanged
})
