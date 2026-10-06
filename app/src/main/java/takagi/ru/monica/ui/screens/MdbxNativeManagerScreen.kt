package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.isUnsupportedGlitter
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.MdbxViewModel

internal data class MdbxNativeCreation(val folderId: String? = null, val draft: AddEditPasswordInitialDraft? = null)

private data class NativeNameAction(val node: MdbxStructureNode? = null, val parent: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MdbxNativeManagerScreen(databaseId: Long, databaseName: String, viewModel: MdbxViewModel, creation: MdbxNativeCreation? = null, onBack: () -> Unit) {
    val databases by viewModel.allDatabases.collectAsState()
    val database = databases.firstOrNull { it.id == databaseId }
    if (database?.isUnsupportedGlitter == true) {
        MdbxUnsupportedModePage(databaseName, onBack)
        return
    }
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
    var editing by remember(databaseId) { mutableStateOf(false) }
    var creationConsumed by rememberSaveable(databaseId) { mutableStateOf(false) }
    var seedCreationDraft by remember(databaseId) { mutableStateOf(false) }
    var editorFailure by remember(databaseId) { mutableStateOf<String?>(null) }
    var deleteConfirmation by remember(databaseId) { mutableStateOf(false) }
    var previewAttachment by remember(databaseId) { mutableStateOf<MdbxNativeAttachment?>(null) }
    // These are external source URIs and an opaque object ID, never decrypted draft content.
    var uploadUris by remember(databaseId) { mutableStateOf(emptyList<Uri>()) }
    var pendingAttachmentObjectId by remember(databaseId) { mutableStateOf<String?>(null) }
    var resumeAttachmentEditor by remember(databaseId) { mutableStateOf(false) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uploadUris = (uploadUris + uris).distinct()
            resumeAttachmentEditor = true
        }
    }
    val scope = rememberCoroutineScope()
    val saved = rememberSaveableStateHolder()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val contentAuthorized = database != null && foreground && unlocked
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!foreground) { seedCreationDraft = false; detail = null; browser = null; nameAction = null; moveAction = null; editing = false; editorFailure = null; deleteConfirmation = false; previewAttachment = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(databaseId, foreground, unlocked, contentAuthorized, revision) {
        if (!foreground || !unlocked || !contentAuthorized) {
            browser = null; detail = null; detailId = null; nameAction = null; moveAction = null
            seedCreationDraft = false; editing = false; editorFailure = null; deleteConfirmation = false; previewAttachment = null
            saved.removeState("browser")
            return@LaunchedEffect
        }
        loading = true; failure = false
        try { browser = viewModel.nativeBrowser(databaseId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true }
        finally { loading = false }
    }
    LaunchedEffect(databaseId, detailId, foreground, unlocked, contentAuthorized, revision) {
        detail = null
        if (detailId == null || !foreground || !unlocked || !contentAuthorized) return@LaunchedEffect
        failure = false
        try { detail = viewModel.nativeObject(databaseId, detailId!!) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true }
    }
    LaunchedEffect(contentAuthorized, browser != null) {
        if (contentAuthorized && browser != null && creation != null && !creationConsumed) {
            creationConsumed = true
            seedCreationDraft = true
            detailId = null
            detail = null
            editing = true
        }
    }
    LaunchedEffect(contentAuthorized, foreground, resumeAttachmentEditor) {
        if (contentAuthorized && foreground && resumeAttachmentEditor) {
            detailId = pendingAttachmentObjectId
            editing = true
            resumeAttachmentEditor = false
        }
    }
    fun mutate(operation: suspend () -> Unit) {
        if (busy || !foreground || !unlocked || !contentAuthorized) return
        busy = true; mutationFailure = false
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutationFailure = true }
            finally { busy = false; revision++ }
        }
    }
    BackHandler(editing || detailId != null || busy) {
        if (!busy) {
            if (editing) { editing = false; seedCreationDraft = false; uploadUris = emptyList(); editorFailure = null; if (creation != null && detailId == null) onBack() }
            else detailId = null
        }
    }
    if (!contentAuthorized) {
        Scaffold(topBar = {
            MdbxTopAppBar(title = { Text(databaseName) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (database != null) {
                    Text(stringResource(R.string.mdbx_native_app_locked),
                        modifier = Modifier.testTag("mdbx_app_locked"))
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }
    if (editing && (detailId == null || detail != null)) {
        MdbxNativeObjectEditor(
            original = detail?.takeIf { detailId != null },
            selectedUris = uploadUris,
            busy = busy,
            failure = editorFailure,
            initialDraft = creation?.draft?.takeIf { seedCreationDraft && detailId == null },
            targetLabel = if (detailId == null) listOf(databaseName).plus(
                MdbxBrowserIndex(browser?.nodes.orEmpty()).ancestors(creation?.folderId).map { it.name }).joinToString(" / ") else null,
            onPickAttachments = {
                pendingAttachmentObjectId = detailId
                attachmentPicker.launch(arrayOf("*/*"))
            },
            onRemoveUpload = { uri -> uploadUris = uploadUris - uri },
            onBack = { editing = false; seedCreationDraft = false; uploadUris = emptyList(); editorFailure = null; if (creation != null && detailId == null) onBack() },
            onSave = { title, type, payload, uploads, removed ->
                if (!busy && contentAuthorized && foreground && unlocked) {
                    val original = detail?.takeIf { detailId != null }
                    busy = true
                    editorFailure = null
                    scope.launch {
                        try {
                            val id = viewModel.saveNativeObject(databaseId, original, title, type, payload, uploads, removed, creation?.folderId.takeIf { original == null })
                            detailId = id
                            seedCreationDraft = false
                            editing = false
                            uploadUris = emptyList()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { editorFailure = error.message }
                        finally { busy = false; revision++ }
                    }
                }
            },
        )
        return
    }
    previewAttachment?.let { attachment ->
        detail?.let { original ->
            MdbxNativeAttachmentPreview(attachment,
                load = { viewModel.readNativeAttachment(databaseId, original, attachment.id) },
                onDismiss = { previewAttachment = null })
        }
    }
    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.mdbx_native_delete_confirm)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val original = detail ?: return@TextButton
                deleteConfirmation = false
                mutate { viewModel.deleteNativeObject(databaseId, original); detailId = null; detail = null }
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (detailId != null) {
        MdbxUnknownEntryContent(title = detail?.summary?.title ?: databaseName,
            type = detail?.summary?.type?.let { mdbxBrowserTypeName(it) }.orEmpty(), payload = detail?.payload,
            failed = failure, onBack = { detailId = null }, notice = stringResource(R.string.mdbx_native_editable),
            allowSelection = true,
            metadataContent = {
                detail?.let { record ->
                    Text(stringResource(R.string.mdbx_native_schema, record.summary.version.toInt()), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { editorFailure = null; editing = true }) { Text(stringResource(R.string.edit)) }
                        TextButton(enabled = !busy, onClick = { deleteConfirmation = true }) { Text(stringResource(R.string.delete)) }
                    }
                    if (mutationFailure) Text(stringResource(R.string.mdbx_native_failed), color = MaterialTheme.colorScheme.error)
                    if (record.attachments.isNotEmpty()) {
                        Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleSmall)
                        record.attachmentRecords.forEach { attachment ->
                            TextButton(enabled = !busy, onClick = { previewAttachment = attachment }) {
                                Text("${attachment.fileName} · ${attachment.size} B")
                            }
                        }
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
            if (browser != null) {
                FilledTonalButton(onClick = { detailId = null; detail = null; editorFailure = null; uploadUris = emptyList(); editing = true },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("native_add_login")) {
                    Text(stringResource(R.string.mdbx_native_add_login))
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
