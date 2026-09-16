package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.viewmodel.MdbxViewModel
import takagi.ru.monica.viewmodel.nativeApiTokenSource

@Composable
internal fun ApiTokenSection(
    title: String,
    icon: ImageVector = Icons.Default.Key,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

/** The same storage card and sheet used by the other editors, restricted to native MDBX. */
@Composable
internal fun ApiTokenStorageSelector(
    viewModel: MdbxViewModel,
    databases: List<LocalMdbxDatabase>,
    databaseId: Long?,
    folderId: String?,
    editing: Boolean,
    enabled: Boolean,
    onSelect: (Long, String?) -> Unit,
    onManageDatabases: () -> Unit,
) {
    val context = LocalContext.current
    val folderDao = remember(context) { PasswordDatabase.getDatabase(context).bitwardenFolderDao() }
    var showPicker by remember { mutableStateOf(false) }
    var folderLoadFailed by remember { mutableStateOf(false) }
    var folderReload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val sources = remember(databases) { databases.map { it.nativeApiTokenSource() } }
    val folderFlows = remember(viewModel, sources, folderReload) {
        databases.associate { db -> db.id to flow { emit(viewModel.nativeApiTokenFolders(db.id)) }
            .flowOn(Dispatchers.IO).catch { folderLoadFailed = true; emit(emptyList()) }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1) }
    }
    val getFolders = remember(folderFlows) { { id: Long -> folderFlows[id] ?: flowOf(emptyList()) } }
    val target = remember(databaseId, folderId) { databaseId?.let { StorageTarget.Mdbx(it, folderId) } }
    if (target == null || databases.none { it.id == databaseId }) {
        ApiTokenSection(stringResource(R.string.api_token_database)) {
            Text(stringResource(R.string.api_token_no_database), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onManageDatabases) { Text(stringResource(R.string.api_token_manage_databases)) }
        }
    } else {
        MultiStorageTargetSelectorCard(
            selectedTargets = listOf(target), existingTargetKeys = emptySet(),
            categories = emptyList(), keepassDatabases = emptyList(), mdbxDatabases = databases,
            bitwardenVaults = emptyList(), bitwardenFolderDao = folderDao, getMdbxFolders = getFolders,
            isEditing = editing, onAddTargetClick = { if (enabled) {
                folderLoadFailed = false
                folderReload++
                showPicker = true
            } },
            onRemoveTarget = {},
        )
    }
    MultiStorageTargetPickerBottomSheet(
        visible = showPicker, selectedTargets = listOfNotNull(target), lockedTargetKeys = emptySet(),
        categories = emptyList(), keepassDatabases = emptyList(), mdbxDatabases = databases,
        bitwardenVaults = emptyList(), getBitwardenFolders = { flowOf(emptyList()) },
        getKeePassGroups = { flowOf(emptyList()) }, getMdbxFolders = getFolders,
        onDismiss = { showPicker = false }, showSelectionModeToggle = false, showMonicaLocal = false,
        onConfirmSelection = { showPicker = false },
        onSelectedTargetsChange = { targets ->
            (targets.singleOrNull() as? StorageTarget.Mdbx)?.let { onSelect(it.databaseId, it.folderId) }
        },
    )
    if (folderLoadFailed) Text(stringResource(R.string.api_token_folder_error),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun ApiTokenError(onRetry: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.api_token_load_error), style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.api_token_reload)) }
        }
    }
}
