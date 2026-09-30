package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.*
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.ui.screens.AddEditNoteScreen
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.NoteEditorViewModel

@Composable
internal fun EmbeddedWalletEditorDialog(snapshot: EmbeddedWalletContent.Snapshot,
    draft: EmbeddedWalletCopyService.Prepared?, parent: PasswordEntry?,
    bankViewModel: BankCardViewModel?, noteViewModel: NoteViewModel?,
    onCopyCard: (() -> Unit)? = null,
    onCopyDocument: (() -> Unit)? = null,
    nativeAccess: EmbeddedWalletAccess? = null,
    onDismiss: () -> Unit, onSaved: (EmbeddedWalletCopyService.Prepared) -> Unit) {
    val context = LocalContext.current
    val removedAssets = remember(snapshot.id) { mutableStateListOf<String>() }
    var exportAsset by remember { mutableStateOf<EmbeddedWalletContent.Asset?>(null) }
    var operationFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var access by remember(snapshot.id, nativeAccess) { mutableStateOf(nativeAccess) }
    var bitmap by remember(snapshot.id) { mutableStateOf<Bitmap?>(null) }
    suspend fun readImage(name: String): Bitmap? = withContext(Dispatchers.IO) {
        if (draft != null && draft.assets.assets.any { it.name == name }) draft.assets.open(name).use { BitmapFactory.decodeStream(it) }
        else if (snapshot.assets.any { it.name == name }) access?.image(name)
        else takagi.ru.monica.util.ImageManager(context).loadImage(name)
    }
    LaunchedEffect(snapshot.id, nativeAccess) {
        if (draft == null && nativeAccess == null && parent != null && snapshot.assets.isNotEmpty()) {
            try { access = EmbeddedWalletAccess.open(context, parent, snapshot) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Save remains blocked if any referenced bytes cannot be read. */ }
        }
        snapshot.assets.firstOrNull { it.role == EmbeddedWalletContent.AssetRole.CARD_FACE }?.let { bitmap = readImage(it.name) }
    }
    DisposableEffect(snapshot.id, nativeAccess) { onDispose { if (nativeAccess == null) access?.close() } }
    val save: suspend (EmbeddedWalletEditorResult) -> Unit = { result ->
        val filtered = result.copy(snapshot = result.snapshot.withAssets(result.snapshot.assets.filterNot { it.name in removedAssets }))
        val prepared = prepareEmbeddedEdit(context, filtered, draft, access)
        try { onSaved(prepared) } catch (error: Throwable) { prepared.close(); throw error }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val asset = exportAsset
        if (uri != null && asset != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                    if (draft != null) draft.assets.open(asset.name).use { it.copyTo(output) }
                    else requireNotNull(access).copyTo(asset.name, output)
                }
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { operationFailed = true }
        }
    }
    val attachments: @Composable () -> Unit = {
        val items = snapshot.assets.filter { it.role == EmbeddedWalletContent.AssetRole.ATTACHMENT && it.name !in removedAssets }
        Column(verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
            items.forEachIndexed { index, asset ->
                ListItem(headlineContent = { Text(asset.displayName) },
                    supportingContent = { Text(android.text.format.Formatter.formatFileSize(context, asset.size)) },
                    leadingContent = { Icon(Icons.Default.AttachFile, null) },
                    trailingContent = { Row {
                        IconButton(onClick = { exportAsset = asset; export.launch(takagi.ru.monica.data.NativeApiTokenAssets.exportName(asset.displayName)) }) {
                            Icon(Icons.Default.Download, stringResource(R.string.save))
                        }
                        IconButton(onClick = { removedAssets.add(asset.name) }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    } },
                    modifier = Modifier.fillMaxWidth().clip(GroupedItemDefaults.shape(index, items.size)),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
        }
    }
    if (operationFailed) AlertDialog(onDismissRequest = { operationFailed = false },
        title = { Text(stringResource(R.string.embedded_copy_failed)) },
        text = { Text(stringResource(R.string.embedded_copy_failed_message, snapshot.title)) },
        confirmButton = { TextButton(onClick = { operationFailed = false }) { Text(stringResource(R.string.cancel)) } })
    val database = remember(context) { takagi.ru.monica.data.PasswordDatabase.getDatabase(context) }
    val security = takagi.ru.monica.ui.rememberUiSecurityManager()
    val repository = remember(database, security) { takagi.ru.monica.repository.SecureItemRepository(database.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        key(snapshot.id) { when (snapshot.kind) {
            EmbeddedWalletContent.Kind.BANK_CARD -> {
                val model = bankViewModel ?: androidx.lifecycle.viewmodel.compose.viewModel {
                    BankCardViewModel(repository, context, database.localKeePassDatabaseDao(), security,
                        strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
                }
                AddEditBankCardScreen(model, onNavigateBack = onDismiss, embeddedDraft = snapshot,
                    embeddedBitmap = bitmap, embeddedImageLoader = ::readImage, embeddedAttachmentsContent = attachments, onEmbeddedCopy = onCopyCard, onEmbeddedSave = save)
            }
            EmbeddedWalletContent.Kind.NOTE -> {
                val model = noteViewModel ?: androidx.lifecycle.viewmodel.compose.viewModel {
                    NoteViewModel(repository, context = context, localKeePassDatabaseDao = database.localKeePassDatabaseDao(), securityManager = security,
                        strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
                }
                val editor = androidx.lifecycle.viewmodel.compose.viewModel<NoteEditorViewModel>(key = "embedded-note-${snapshot.id}") {
                    NoteEditorViewModel(persistDrafts = false)
                }
                AddEditNoteScreen(-1, onNavigateBack = onDismiss, viewModel = model, editorViewModel = editor,
                    embeddedDraft = snapshot, embeddedImageLoader = ::readImage, embeddedAttachmentsContent = attachments, onEmbeddedSave = save)
            }
            EmbeddedWalletContent.Kind.DOCUMENT -> {
                val model = androidx.lifecycle.viewmodel.compose.viewModel {
                    takagi.ru.monica.viewmodel.DocumentViewModel(repository, context,
                        database.localKeePassDatabaseDao(), security,
                        strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
                }
                AddEditDocumentScreen(model, onNavigateBack = onDismiss, embeddedDraft = snapshot,
                    embeddedBitmap = bitmap, embeddedImageLoader = ::readImage,
                    embeddedAttachmentsContent = attachments, onEmbeddedCopy = onCopyDocument, onEmbeddedSave = save)
            }
            EmbeddedWalletContent.Kind.ADDRESS -> {
                val model = androidx.lifecycle.viewmodel.compose.viewModel {
                    takagi.ru.monica.viewmodel.BillingAddressViewModel(repository, security, context)
                }
                takagi.ru.monica.ui.screens.AddEditBillingAddressScreen(model, onNavigateBack = onDismiss,
                    embeddedDraft = snapshot, embeddedBitmap = bitmap, embeddedAttachmentsContent = attachments,
                    onEmbeddedSave = save)
            }
        } }
    }
}
