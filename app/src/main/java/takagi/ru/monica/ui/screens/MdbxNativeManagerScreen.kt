package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.MdbxViewModel

private data class NativeNameAction(val node: MdbxStructureNode? = null, val parent: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MdbxNativeManagerScreen(databaseId: Long, databaseName: String, viewModel: MdbxViewModel, onBack: () -> Unit) {
    var browser by remember(databaseId) { mutableStateOf<MdbxNativeBrowser?>(null) }
    var detailId by rememberSaveable(databaseId) { mutableStateOf<String?>(null) }
    var detail by remember(databaseId) { mutableStateOf<MdbxNativeObjectDetail?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var mutationFailure by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var nameAction by remember { mutableStateOf<NativeNameAction?>(null) }
    var moveAction by remember { mutableStateOf<MdbxStructureNode?>(null) }
    val scope = rememberCoroutineScope()
    val saved = rememberSaveableStateHolder()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!foreground) { detail = null; browser = null; nameAction = null; moveAction = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(databaseId, foreground, unlocked, revision) {
        if (!foreground || !unlocked) { browser = null; detail = null; nameAction = null; moveAction = null; return@LaunchedEffect }
        loading = true; failure = false
        try { browser = viewModel.nativeBrowser(databaseId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true }
        finally { loading = false }
    }
    LaunchedEffect(databaseId, detailId, foreground, unlocked, revision) {
        detail = null
        if (detailId == null || !foreground || !unlocked) return@LaunchedEffect
        failure = false
        try { detail = viewModel.nativeObject(databaseId, detailId!!) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true }
    }
    fun mutate(operation: suspend () -> Unit) {
        if (busy || !foreground || !unlocked) return
        busy = true; mutationFailure = false
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutationFailure = true }
            finally { busy = false; revision++ }
        }
    }
    BackHandler(detailId != null || busy) { if (!busy) detailId = null }
    if (detailId != null) {
        MdbxUnknownEntryContent(title = detail?.summary?.title ?: databaseName,
            type = detail?.summary?.type?.let { mdbxBrowserTypeName(it) }.orEmpty(), payload = detail?.payload,
            failed = failure, onBack = { detailId = null }, notice = stringResource(R.string.mdbx_native_readonly),
            metadataContent = {
                detail?.let { record ->
                    Text(stringResource(R.string.mdbx_native_schema, record.summary.version.toInt()), style = MaterialTheme.typography.labelMedium)
                    if (record.attachments.isNotEmpty()) {
                        Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleSmall)
                        record.attachments.forEach { (name, size) -> Text("$name · $size B", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            })
        return
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure || mutationFailure) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.mdbx_native_failed))
                    TextButton(onClick = { mutationFailure = false; revision++ }, enabled = !busy) { Text(stringResource(R.string.refresh)) }
                }
            }
            browser?.let { state ->
                saved.SaveableStateProvider("browser") {
                MdbxFolderBrowser(state.nodes, databaseName, Modifier.weight(1f), onEntry = { detailId = it.id },
                    onRename = { nameAction = NativeNameAction(it) }, onMoveFolder = { moveAction = it },
                    onCreateFolder = { nameAction = NativeNameAction(parent = it) }, onRefresh = { revision++ }, onBack = onBack, enabled = !busy)
                }
            }
        }
    }
    nameAction?.let { action ->
        var name by remember(action) { mutableStateOf(action.node?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { nameAction = null }, title = {
            Text(stringResource(if (action.node == null) R.string.keepass_native_create_group else R.string.rename_category))
        }, text = {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true)
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.length <= 512, onClick = {
                nameAction = null
                val node = action.node
                val expected = node?.let { browser?.objects?.get(it.id) }
                mutate {
                    when {
                        node == null -> viewModel.createNativeFolder(databaseId, name.trim(), action.parent)
                        node.type == MdbxStructureNodeType.FOLDER -> viewModel.renameNativeFolder(databaseId, node.id, name.trim())
                        else -> viewModel.renameNativeObject(databaseId, requireNotNull(expected), name.trim())
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        }, dismissButton = { TextButton(onClick = { nameAction = null }) { Text(stringResource(R.string.cancel)) } })
    }
    moveAction?.let { source ->
        val index = remember(browser) { MdbxBrowserIndex(browser?.nodes.orEmpty()) }
        AlertDialog(onDismissRequest = { moveAction = null }, title = { Text(stringResource(R.string.move)) }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                item {
                    TextButton(onClick = { moveAction = null; mutate { viewModel.moveNativeFolder(databaseId, source.id, null) } }) {
                        Text(databaseName)
                    }
                }
                items(index.folders.values.filter { index.canMoveFolder(source.id, it.id) }.sortedBy { it.name }, key = { it.id }) { folder ->
                    TextButton(onClick = { moveAction = null; mutate { viewModel.moveNativeFolder(databaseId, source.id, folder.id) } }) {
                        Text(index.ancestors(folder.id).joinToString(" / ") { it.name })
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { moveAction = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
