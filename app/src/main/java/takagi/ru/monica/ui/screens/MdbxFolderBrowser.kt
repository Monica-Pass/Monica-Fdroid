package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.repository.*
import java.util.Locale

/** Folder navigation shared by the live native manager and both read-only snapshot panes. */
@Composable
internal fun MdbxFolderBrowser(
    nodes: List<MdbxStructureNode>,
    rootName: String,
    modifier: Modifier = Modifier,
    paneId: String = "native",
    heading: String = "",
    onEntry: (MdbxStructureNode) -> Unit,
    onRename: ((MdbxStructureNode) -> Unit)? = null,
    onMoveFolder: ((MdbxStructureNode) -> Unit)? = null,
    onCreateFolder: ((String?) -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val index = remember(nodes) { MdbxBrowserIndex(nodes) }
    var folderId by rememberSaveable(paneId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(paneId) { mutableStateOf("") }
    var searching by rememberSaveable(paneId) { mutableStateOf(false) }
    var descending by rememberSaveable(paneId) { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val scrollStates = rememberSaveableStateHolder()
    val currentId = folderId?.takeIf { it in index.folders }
    val chain = remember(index, currentId) { index.ancestors(currentId) }
    BackHandler(query.isNotEmpty() || searching || currentId != null) {
        when {
            query.isNotEmpty() -> query = ""
            searching -> searching = false
            else -> folderId = chain.dropLast(1).lastOrNull()?.id
        }
    }
    val typeNames = nodes.filter { it.type == MdbxStructureNodeType.ENTRY }.map { it.metadata }.distinct()
        .associateWith { mdbxBrowserTypeName(it) }
    val visible = remember(index, currentId, query, descending, typeNames) {
        val source = if (query.isBlank()) index.children(currentId) else index.nodes.filter {
            it.name.contains(query.trim(), true) || it.metadata.contains(query.trim(), true) ||
                typeNames[it.metadata].orEmpty().contains(query.trim(), true)
        }
        source.sortedWith(compareBy<MdbxStructureNode> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
            .let { if (descending) it.asReversed() else it }
    }
    val folders = visible.filter { it.type == MdbxStructureNodeType.FOLDER }
    val entries = visible.filter { it.type == MdbxStructureNodeType.ENTRY }
    Column(modifier.fillMaxSize().testTag("mdbx-browser-$paneId")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(enabled = enabled, onClick = {
                when {
                    query.isNotEmpty() -> query = ""
                    searching -> searching = false
                    currentId != null -> folderId = chain.dropLast(1).lastOrNull()?.id
                    else -> onBack()
                }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(heading.ifEmpty { index.folders[currentId]?.name ?: rootName },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onBack != null) Text(if (currentId == null) stringResource(R.string.mdbx_native_title) else rootName,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(Icons.Default.Search, stringResource(R.string.search))
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, stringResource(R.string.keepass_native_sort)) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf(false to R.string.keepass_native_sort_title_ascending, true to R.string.keepass_native_sort_title_descending).forEach { (desc, label) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { descending = desc; sortMenu = false },
                            trailingIcon = { if (descending == desc) Icon(Icons.Default.Check, null) })
                    }
                }
            }
            if (onRefresh != null) IconButton(onClick = onRefresh, enabled = enabled) {
                Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
            }
        }
        if (searching) OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.mdbx_native_search)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("mdbx-search-$paneId"),
            shape = RoundedCornerShape(24.dp), trailingIcon = {
                IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("mdbx-path-$paneId"),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { folderId = null; query = "" }) {
                Icon(Icons.Default.Folder, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(rootName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            }
            chain.forEach { folder ->
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp))
                TextButton(onClick = { folderId = folder.id; query = "" }) {
                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
            }
        }
        Text(stringResource(R.string.keepass_native_group_counts, folders.size, entries.size),
            Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            scrollStates.SaveableStateProvider("${currentId.orEmpty()}:${query.isNotBlank()}:$descending") {
                LazyColumn(Modifier.fillMaxSize().testTag("mdbx-list-$paneId"),
                    contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (visible.isEmpty()) item {
                        Text(stringResource(if (query.isBlank()) R.string.keepass_native_empty_group else R.string.no_results),
                            Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    fun section(label: Int, rows: List<MdbxStructureNode>) {
                        if (rows.isEmpty()) return
                        item(key = "section:$label") {
                            Text(stringResource(label), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        itemsIndexed(rows, key = { _, row -> "${row.type}:${row.id}" }) { position, node ->
                            val isFolder = node.type == MdbxStructureNodeType.FOLDER
                            var menu by remember { mutableStateOf(false) }
                            val childNodes = index.children(node.id)
                            Surface(onClick = { if (isFolder) { folderId = node.id; query = "" } else onEntry(node) },
                                shape = settingsSectionItemShape(position, rows.size), enabled = enabled,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().testTag("mdbx-row-$paneId-${node.id}")) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Icon(if (isFolder) Icons.Default.Folder else mdbxBrowserTypeIcon(node.metadata), null,
                                            Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(node.name.ifBlank { stringResource(R.string.keepass_native_untitled_entry) },
                                            style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (isFolder) stringResource(R.string.keepass_native_group_counts,
                                            childNodes.count { it.type == MdbxStructureNodeType.FOLDER }, childNodes.count { it.type == MdbxStructureNodeType.ENTRY })
                                            else typeNames[node.metadata].orEmpty(), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (query.isNotBlank()) Text((listOf(rootName) + index.ancestors(index.parent(node)).map { it.name }).joinToString(" / "),
                                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (node.status != MdbxStructureNodeStatus.UNCHANGED) Text(structureStatusLabel(node.status),
                                            style = MaterialTheme.typography.labelMedium, color = structureStatusColor(node.status))
                                    }
                                    if (onRename != null) Box {
                                        IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                                        DropdownMenu(menu, { menu = false }) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.rename_category)) }, onClick = { menu = false; onRename(node) })
                                            if (isFolder && onMoveFolder != null) DropdownMenuItem(text = { Text(stringResource(R.string.move)) },
                                                onClick = { menu = false; onMoveFolder(node) })
                                        }
                                    } else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(8.dp).size(20.dp))
                                }
                            }
                        }
                    }
                    section(R.string.keepass_native_groups, folders)
                    section(R.string.keepass_native_entries, entries)
                }
            }
        }
        if (onCreateFolder != null) FilledTonalButton(onClick = { onCreateFolder(currentId) }, enabled = enabled,
            modifier = Modifier.padding(vertical = 6.dp)) {
            Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.keepass_native_create_group))
        }
    }
}

@Composable
internal fun mdbxBrowserTypeName(type: String): String = when (type.lowercase(Locale.ROOT)) {
    "login", "password" -> stringResource(R.string.entry_type_password)
    "totp", "hotp", "authenticator" -> stringResource(R.string.data_type_totp)
    "bankcard", "bank_card", "bank-card", "card", "credit-card" -> stringResource(R.string.data_type_bank_cards)
    "document-ref", "document", "identity" -> stringResource(R.string.data_type_documents)
    "billing-address" -> stringResource(R.string.billing_address)
    "payment-account" -> stringResource(R.string.payment_account)
    "note", "secure-note" -> stringResource(R.string.notes)
    "passkey" -> stringResource(R.string.passkey)
    "wifi" -> stringResource(R.string.entry_type_wifi)
    "ssh", "ssh-key", "ssh_key" -> stringResource(R.string.entry_type_ssh_key)
    "steam", "steam-mafile" -> "Steam"
    "api-token", "api_token" -> stringResource(R.string.entry_type_api_token)
    "api-key", "api_key" -> stringResource(R.string.api_key_title)
    "gpg", "gpg-key", "gpg_key" -> stringResource(R.string.gpg_title)
    else -> type
}

private fun mdbxBrowserTypeIcon(type: String) = when (type.lowercase(Locale.ROOT)) {
    "login", "password", "api-token", "api-key", "ssh-key", "gpg-key" -> Icons.Default.Key
    "totp", "hotp", "authenticator" -> Icons.Default.Timer
    "bank_card", "bank-card", "card", "credit-card" -> Icons.Default.CreditCard
    "wifi" -> Icons.Default.Wifi
    "passkey" -> Icons.Default.Fingerprint
    else -> Icons.Default.Description
}
