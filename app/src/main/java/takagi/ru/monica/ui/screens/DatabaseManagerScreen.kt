package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.util.Locale

@Stable
internal class DatabaseManagerPane(initial: String? = null) {
    var databaseKey by mutableStateOf(initial)
    var folderId by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var search by mutableStateOf(false)
    var descending by mutableStateOf(false)
    var selected by mutableStateOf(emptySet<String>())
    var snapshot by mutableStateOf<DatabaseManagerSnapshot?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    val location get() = databaseKey?.let { DatabaseManagerLocation(ImportDestination.fromKey(it), folderId) }
    fun navigate(key: String?, folder: String? = null) {
        databaseKey = key; folderId = folder; query = ""; selected = emptySet(); error = null
    }
    companion object {
        val Saver = listSaver<DatabaseManagerPane, Any>(save = {
            listOf(it.databaseKey.orEmpty(), it.folderId.orEmpty(), it.query, it.search, it.descending, it.selected.toList())
        }, restore = {
            DatabaseManagerPane((it[0] as String).ifBlank { null }).apply {
                folderId = (it[1] as String).ifBlank { null }; query = it[2] as String; search = it[3] as Boolean
                descending = it[4] as Boolean; selected = (it[5] as List<*>).filterIsInstance<String>().toSet()
            }
        })
    }
}

private data class ManagerNameRequest(val location: DatabaseManagerLocation, val row: MdbxStructureNode? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseManagerScreen(initialDatabaseKey: String?, mdbxViewModel: MdbxViewModel,
    keepassViewModel: LocalKeePassViewModel, onBack: () -> Unit,
    onManageDatabase: (ImportDestination) -> Unit = {},
    onNativeEntry: (takagi.ru.monica.keepass.KeePassNativeResolvedRoute) -> Unit = {}) {
    val context = LocalContext.current
    val mdbxDatabases by mdbxViewModel.allDatabases.collectAsState()
    val mdbxDatabasesLoaded by mdbxViewModel.allDatabasesLoaded.collectAsState()
    val repo = remember { DatabaseManagerRepository(context) }
    val left = rememberSaveable(saver = DatabaseManagerPane.Saver) { DatabaseManagerPane(initialDatabaseKey) }
    val right = rememberSaveable(saver = DatabaseManagerPane.Saver) { DatabaseManagerPane() }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var twoPanes by rememberSaveable { mutableStateOf(landscape) }
    var active by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stores by remember { mutableStateOf(emptyList<DatabaseManagerStore>()) }
    var nameRequest by remember { mutableStateOf<ManagerNameRequest?>(null) }
    var report by remember { mutableStateOf<DatabaseManagerReport?>(null) }
    var detail by remember { mutableStateOf<Pair<DatabaseManagerLocation, MdbxStructureNode>?>(null) }
    var transferCopy by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!foreground) { left.snapshot = null; right.snapshot = null; detail = null; nameRequest = null; transferCopy = null; report = null }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, foreground, unlocked) {
        if (foreground && unlocked) stores = repo.stores() else {
            stores = emptyList(); detail = null; nameRequest = null; transferCopy = null; report = null
        }
    }
    listOf(left, right).forEach { pane ->
        LaunchedEffect(pane.databaseKey, revision, foreground, unlocked, mdbxDatabases, mdbxDatabasesLoaded) {
            pane.snapshot = null
            if (!foreground || !unlocked) return@LaunchedEffect
            val location = pane.location ?: return@LaunchedEffect
            if (location.database.mdbxId != null && (!mdbxDatabasesLoaded || mdbxDatabases.any {
                it.id == location.database.mdbxId && (it.tigaModeEnum == takagi.ru.monica.data.MdbxTigaMode.GLITTER ||
                    takagi.ru.monica.repository.MdbxClientModePolicy.isEnvelope(it.encryptedPassword))
            })) return@LaunchedEffect
            pane.loading = true; pane.error = null
            try { pane.snapshot = repo.browse(location) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pane.error = error.message.orEmpty() }
            finally { pane.loading = false }
        }
    }
    suspend fun refreshDatabases(vararg keys: ImportDestination) {
        keys.distinct().forEach { key ->
            if (key.mdbxId != null) mdbxViewModel.refreshManagerProjection(key.databaseId)
        }
    }
    fun mutate(location: DatabaseManagerLocation, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { operation(); refreshDatabases(location.database) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report = DatabaseManagerReport(0, listOf(DatabaseManagerFailure("", error.message.orEmpty()))) }
            finally { busy = false; revision++ }
        }
    }
    val current = if (active == 0) left else right
    val glitterDatabase = mdbxDatabases.firstOrNull {
        it.id == current.location?.database?.mdbxId && (it.tigaModeEnum == takagi.ru.monica.data.MdbxTigaMode.GLITTER ||
            takagi.ru.monica.repository.MdbxClientModePolicy.isEnvelope(it.encryptedPassword))
    }
    // Browsing the destination must not discard the opposite pane's transfer selection.
    val transferSource = if (databaseManagerSourcePane(active, left.selected.size, right.selected.size, twoPanes) == 0) left else right
    val transferTarget = if (transferSource === left) right else left
    fun back() {
        when {
            busy -> Unit
            current.selected.isNotEmpty() -> current.selected = emptySet()
            current.query.isNotBlank() -> current.query = ""
            current.search -> current.search = false
            current.folderId != null -> current.navigate(current.databaseKey, current.snapshot?.index?.folders?.get(current.folderId)?.parentId)
            current.databaseKey != null -> current.navigate(null)
            else -> onBack()
        }
    }
    if (glitterDatabase != null) {
        MdbxUnsupportedModePage(glitterDatabase.name) {
            current.navigate(null)
        }
        return
    }
    BackHandler { if (detail != null) detail = null else back() }
    detail?.let { (location, row) ->
        when (location.database.kind) {
            ImportDestinationKind.KEEPASS -> {
                val databases by keepassViewModel.allDatabases.collectAsState()
                databases.firstOrNull { it.id == location.database.databaseId }?.let { database ->
                    KeePassNativeManagerScreen(database, keepassViewModel, { detail = null; revision++ }, onNativeEntry,
                        initialEntryUuid = row.id)
                }
            }
            else -> {
                var payload by remember(location, row.id) { mutableStateOf<String?>(null) }
                var failure by remember { mutableStateOf(false) }
                LaunchedEffect(location, row.id, unlocked, foreground) {
                    payload = null
                    if (!unlocked || !foreground) return@LaunchedEffect
                    try { payload = withContext(Dispatchers.IO) { managerDetailPayload(repo, location, row) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failure = true }
                }
                MdbxUnknownEntryContent(row.name, mdbxBrowserTypeName(row.metadata), payload, failure, { detail = null },
                    notice = stringResource(R.string.mdbx_native_readonly))
            }
        }
        return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.mdbx_native_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { twoPanes = !twoPanes; active = 0 }, enabled = !busy) {
                    Icon(if (twoPanes) Icons.Default.ViewAgenda else Icons.Default.VerticalSplit, stringResource(R.string.manager_dual))
                }
                IconButton(onClick = { revision++ }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            })
    }, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                if (transferSource.selected.isNotEmpty()) {
                    val other = transferTarget
                    Text(stringResource(R.string.manager_selection_destination, transferSource.selected.size,
                        other.snapshot?.index?.folders?.get(other.folderId)?.name ?: other.snapshot?.title ?: stringResource(R.string.manager_choose_destination)),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!twoPanes) FilledTonalButton(onClick = { twoPanes = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.manager_choose_destination))
                        } else {
                            FilledTonalButton(onClick = { transferCopy = true }, enabled = !busy && other.location != null && other.error == null,
                                modifier = Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.copy)) }
                            Button(onClick = { transferCopy = false }, enabled = !busy && other.location != null && other.error == null,
                                modifier = Modifier.weight(1f)) { Icon(Icons.Default.DriveFileMove, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.move)) }
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { current.location?.let { nameRequest = ManagerNameRequest(it) } }, enabled = current.location != null && !busy) {
                        Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.keepass_native_create_group))
                    }
                    TextButton(onClick = { current.search = !current.search }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                }
            }
        }
    }) { padding ->
        if (foreground && unlocked) Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(left, right).take(if (twoPanes) 2 else 1).forEachIndexed { index, pane ->
                DatabaseManagerPaneContent(pane, stores, index.toString(), twoPanes, active == index, !busy,
                    onActivate = { active = index }, onOpen = { row -> pane.location?.let { detail = it to row } },
                    onRename = { row -> pane.location?.let { nameRequest = ManagerNameRequest(it, row) } },
                    modifier = Modifier.weight(1f),
                    onCreateFolder = { pane.location?.let { nameRequest = ManagerNameRequest(it) } },
                    onManageDatabase = { pane.location?.database?.let(onManageDatabase) })
            }
        }
    }
    nameRequest?.let { request ->
        var name by remember(request) { mutableStateOf(request.row?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { nameRequest = null }, title = { Text(stringResource(if (request.row == null) R.string.keepass_native_create_group else R.string.rename_category)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.title)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && name.length <= 512, onClick = {
                nameRequest = null; mutate(request.location) {
                    if (request.row == null) repo.createFolder(request.location, name.trim()) else repo.rename(request.location, request.row, name.trim())
                }
            }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { nameRequest = null }) { Text(stringResource(R.string.cancel)) } })
    }
    transferCopy?.let { copy ->
        val sourcePane = transferSource
        val target = transferTarget
        AlertDialog(onDismissRequest = { transferCopy = null }, title = { Text(stringResource(if (copy) R.string.copy else R.string.move)) },
            text = { Text(stringResource(R.string.manager_confirm_transfer, sourcePane.selected.size,
                sourcePane.snapshot?.title.orEmpty(), target.snapshot?.title.orEmpty(),
                target.snapshot?.index?.ancestors(target.folderId)?.joinToString(" / ") { it.name }.orEmpty())) },
            confirmButton = { TextButton(onClick = {
                transferCopy = null
                val from = sourcePane.location ?: return@TextButton
                val to = target.location ?: return@TextButton
                val selected = sourcePane.selected
                val observedRevision = sourcePane.snapshot?.revision
                busy = true; progress = 0f
                scope.launch {
                    try {
                        report = repo.transfer(from, to, selected, copy, observedRevision) { done, total -> progress = done.toFloat() / total.coerceAtLeast(1) }
                        sourcePane.selected = emptySet()
                        refreshDatabases(from.database, to.database)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { report = DatabaseManagerReport(0, listOf(DatabaseManagerFailure("", error.message.orEmpty()))) }
                    finally { busy = false; revision++ }
                }
            }) { Text(stringResource(if (copy) R.string.copy else R.string.move)) } },
            dismissButton = { TextButton(onClick = { transferCopy = null }) { Text(stringResource(R.string.cancel)) } })
    }
    report?.takeIf { foreground && unlocked }?.let { result ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text(stringResource(R.string.manager_result)) },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.manager_result_count, result.completed, result.failures.size)) }
                items(result.failures) { failure ->
                    Column { Text(failure.title, style = MaterialTheme.typography.titleSmall)
                        Text(managerFailureText(failure.reason), style = MaterialTheme.typography.bodyMedium)
                        var technical by remember { mutableStateOf(false) }
                        TextButton(onClick = { technical = !technical }) { Text(stringResource(R.string.manager_technical_details)) }
                        if (technical) Text(failure.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
internal fun DatabaseManagerPaneContent(pane: DatabaseManagerPane, stores: List<DatabaseManagerStore>, paneId: String,
    compact: Boolean, active: Boolean, enabled: Boolean, onActivate: () -> Unit,
    onOpen: (MdbxStructureNode) -> Unit, onRename: (MdbxStructureNode) -> Unit, modifier: Modifier = Modifier,
    onCreateFolder: () -> Unit = {}, onManageDatabase: () -> Unit = {}) {
    val index = pane.snapshot?.index
    val folderId = pane.folderId?.takeIf { it in index?.folders.orEmpty() }
    val typeNames = pane.snapshot?.nodes.orEmpty().map { it.metadata }.distinct().associateWith { mdbxBrowserTypeName(it) }
    val visible = remember(pane.snapshot, folderId, pane.query, pane.descending, typeNames) {
        (if (pane.query.isBlank()) index?.children(folderId).orEmpty() else index?.nodes.orEmpty().filter {
            it.name.contains(pane.query, true) || typeNames[it.metadata].orEmpty().contains(pane.query, true)
        }).sortedWith(compareBy<MdbxStructureNode> { it.type != MdbxStructureNodeType.FOLDER }
            .thenComparator { a, b -> (if (pane.descending) -1 else 1) * a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT)) }.thenBy { it.id })
    }
    val stateHolder = rememberSaveableStateHolder()
    Column(modifier.fillMaxHeight().testTag("manager-pane-$paneId")) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (active && compact) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(), onClick = onActivate) {
            Row(Modifier.heightIn(min = if (compact) 40.dp else 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pane.snapshot?.title ?: stringResource(R.string.manager_databases), Modifier.weight(1f),
                    fontSize = if (compact) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { onActivate(); menu = true }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), Modifier.size(20.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.manager_databases)) }, onClick = { menu = false; pane.navigate(null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.search)) }, onClick = { menu = false; pane.search = !pane.search })
                        if (pane.location != null) DropdownMenuItem(text = { Text(stringResource(R.string.keepass_native_create_group)) }, onClick = { menu = false; onCreateFolder() })
                    }
                }
            }
        }
        if (pane.databaseKey != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("manager-path-$paneId"), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onActivate(); pane.navigate(pane.databaseKey) }, modifier = if (compact) Modifier.height(36.dp) else Modifier, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("/", fontSize = 12.sp)
                }
                index?.ancestors(folderId).orEmpty().forEach { folder ->
                    TextButton(onClick = { onActivate(); pane.navigate(pane.databaseKey, folder.id) }, modifier = if (compact) Modifier.height(36.dp) else Modifier, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(folder.name, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selectionDescription = stringResource(R.string.manager_items_selected, visible.size, pane.selected.size)
                Text(if (compact) "${visible.size} · ✓ ${pane.selected.size}" else selectionDescription,
                    Modifier.weight(1f).semantics { contentDescription = selectionDescription },
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onActivate(); pane.selected = if (pane.selected.containsAll(visible.map { it.id })) emptySet() else visible.map { it.id }.toSet() }, modifier = Modifier.size(32.dp), enabled = enabled) {
                    Icon(Icons.Default.SelectAll, stringResource(R.string.select_all), Modifier.size(18.dp))
                }
                IconButton(onClick = { pane.descending = !pane.descending }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Sort, stringResource(R.string.keepass_native_sort), Modifier.size(18.dp)) }
            }
        }
        if (pane.search) OutlinedTextField(pane.query, { pane.query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("manager-search-$paneId"), shape = RoundedCornerShape(16.dp),
            placeholder = { Text(stringResource(R.string.search), fontSize = 12.sp) })
        if (pane.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        pane.error?.let { error ->
            Text(managerFailureText(error), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onManageDatabase) { Text(stringResource(R.string.manager_open_database)) }
        }
        if (pane.databaseKey == null) LazyColumn(Modifier.weight(1f).testTag("manager-list-$paneId"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            stores.filter { it.title.contains(pane.query, true) }.groupBy { it.key.kind }.forEach { (kind, databases) ->
                item("section:$kind") { Text(when (kind) { ImportDestinationKind.LOCAL -> "Monica"; ImportDestinationKind.MDBX -> "MDBX"; else -> "KeePass" },
                    Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                itemsIndexed(databases, key = { _, store -> store.key.key }) { position, store ->
                    Surface(onClick = { onActivate(); pane.navigate(store.key.key) }, shape = settingsSectionItemShape(position, databases.size),
                        modifier = Modifier.testTag("manager-store-$paneId-${store.key.key}"),
                        color = MaterialTheme.colorScheme.surfaceContainerLow, enabled = store.available && enabled) {
                        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 64.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Storage, null, Modifier.size(if (compact) 20.dp else 24.dp))
                            Text(store.title, fontSize = if (compact) 13.sp else 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else stateHolder.SaveableStateProvider("${pane.databaseKey}:$folderId:${pane.query.isNotBlank()}") {
            val state = rememberLazyListState()
            ManagerEntryList(visible, pane, paneId, compact, enabled, state, onActivate, onOpen, onRename,
                onParent = { pane.navigate(pane.databaseKey, index?.folders?.get(folderId)?.parentId) }, hasParent = folderId != null,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ManagerEntryList(rows: List<MdbxStructureNode>, pane: DatabaseManagerPane, paneId: String, compact: Boolean,
    enabled: Boolean, state: LazyListState, onActivate: () -> Unit, onOpen: (MdbxStructureNode) -> Unit,
    onRename: (MdbxStructureNode) -> Unit, onParent: () -> Unit, hasParent: Boolean, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    var edgeScroll by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var anchor by remember { mutableIntStateOf(-1) }
    var initial by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(true) }
    val latestRows by rememberUpdatedState(rows)
    fun updateAt(y: Float) {
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return
        val position = latestRows.indexOfFirst { it.id == item.key }
        if (anchor >= 0 && position >= 0) pane.selected = databaseManagerSelectionRange(initial, latestRows.map { it.id }, anchor, position, selecting)
    }
    LaunchedEffect(edgeScroll) {
        while (edgeScroll != 0f) { withFrameNanos { }; state.scrollBy(edgeScroll); updateAt(dragY) }
    }
    val selectLabel = stringResource(R.string.select_all)
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        val contentWidth = if (compact) maxOf(maxWidth, minOf(360.dp, ((rows.maxOfOrNull { it.name.length } ?: 0) * 6 + 64).dp)) else maxWidth
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = anchor < 0)) {
            LazyColumn(state = state, userScrollEnabled = anchor < 0 && enabled, modifier = Modifier.width(contentWidth).fillMaxHeight().testTag("manager-list-$paneId")
                .pointerInput(rows, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        val position = held.position
                        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
                        anchor = rows.indexOfFirst { it.id == item?.key }
                        if (anchor < 0) return@awaitEachGesture
                        try {
                            held.consume()
                            onActivate(); initial = pane.selected; selecting = rows[anchor].id !in initial
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragY = position.y; updateAt(dragY)
                            while (true) {
                                // Consume release before the row's clickable handles it, including
                                // a long press with no movement, so selection is not toggled off.
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == held.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                dragY = change.position.y; updateAt(dragY)
                                edgeScroll = when { dragY < 48 -> -12f; dragY > size.height - 48 -> 12f; else -> 0f }
                            }
                        } finally { anchor = -1; edgeScroll = 0f }
                    }
                }, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (hasParent) item("parent") { TextButton(onClick = { onActivate(); onParent() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("..")
                } }
                itemsIndexed(rows, key = { _, row -> row.id }) { position, row ->
                    val selected = row.id in pane.selected
                    var menu by remember { mutableStateOf(false) }
                    Surface(shape = settingsSectionItemShape(position, rows.size),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().testTag("manager-row-$paneId-${row.id}").semantics {
                            customActions = listOf(CustomAccessibilityAction(selectLabel) {
                                onActivate(); pane.selected = if (selected) pane.selected - row.id else pane.selected + row.id; true
                            })
                        }.clip(settingsSectionItemShape(position, rows.size)).clickable(enabled) {
                            onActivate()
                            when {
                                pane.selected.isNotEmpty() -> pane.selected = if (selected) pane.selected - row.id else pane.selected + row.id
                                row.type == MdbxStructureNodeType.FOLDER -> pane.navigate(pane.databaseKey, row.id)
                                else -> onOpen(row)
                            }
                        }) {
                        Row(Modifier.heightIn(min = if (compact) 50.dp else 64.dp).padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                            Icon(if (selected) Icons.Default.CheckCircle else if (row.type == MdbxStructureNodeType.FOLDER) Icons.Default.Folder else Icons.Default.Description,
                                null, Modifier.size(if (compact) 20.dp else 24.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(row.name, fontSize = if (compact) 12.sp else 16.sp, lineHeight = if (compact) 16.sp else 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.type != MdbxStructureNodeType.FOLDER) Text(mdbxBrowserTypeName(row.metadata),
                                    fontSize = if (compact) 10.sp else 12.sp, lineHeight = if (compact) 14.sp else 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(if (compact) 32.dp else 40.dp)) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), Modifier.size(18.dp))
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.rename_category)) }, onClick = { menu = false; onRename(row) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.manager_select)) }, onClick = {
                                        menu = false; onActivate(); pane.selected = if (selected) pane.selected - row.id else pane.selected + row.id
                                    })
                                }
                            }
                        }
                    }
                }
                if (rows.isEmpty()) item { Text(stringResource(R.string.keepass_native_empty_group), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun managerFailureText(reason: String) = stringResource(when {
    reason.contains("lossless", true) || reason.contains("schema", true) || reason.contains("format", true) || reason.contains("metadata", true) || reason.contains("legacy image", true) || reason.contains("references", true) -> R.string.manager_failure_format
    reason.contains("changed", true) || reason.contains("verification", true) || reason.contains("differs", true) -> R.string.manager_failure_changed
    reason.contains("locked", true) || reason.contains("unlock", true) || reason.contains("password", true) -> R.string.manager_failure_locked
    else -> R.string.manager_failure_general
})
