package takagi.ru.monica.attachments.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.attachments.EmbeddedWalletAccess
import takagi.ru.monica.data.NativeApiTokenAttachment
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.ui.components.GroupedItemDefaults

/** Shared grouped rows, with native byte access instead of an invented Room owner. */
@Composable
fun NativeApiTokenAttachmentList(attachments: List<NativeApiTokenAttachment>,
    onRead: (suspend (NativeApiTokenAttachment, java.io.OutputStream) -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null, enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<NativeApiTokenAttachment?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val asset = selected
        selected = null
        if (uri != null && asset != null && onRead != null) scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { onRead(asset, it) }
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("native_token_attachments"), verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
        attachments.forEachIndexed { index, asset ->
            ListItem(headlineContent = { Text(asset.fileName) },
                supportingContent = { Text(android.text.format.Formatter.formatFileSize(context, asset.size)) },
                leadingContent = { Icon(Icons.Default.AttachFile, null) },
                trailingContent = { Row {
                    if (onRead != null) IconButton(enabled = enabled && !busy, onClick = {
                        selected = asset; export.launch(takagi.ru.monica.data.NativeApiTokenAssets.exportName(asset.fileName))
                    }) {
                        Icon(Icons.Default.Download, stringResource(R.string.save))
                    }
                    if (onRemove != null) IconButton(enabled = enabled && !busy, onClick = { onRemove(asset.id) }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete))
                    }
                } }, modifier = Modifier.fillMaxWidth().clip(GroupedItemDefaults.shape(index, attachments.size)),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.native_token_assets_error), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun NativeEmbeddedWalletAttachments(access: EmbeddedWalletAccess) {
    val attachments = access.snapshot.assets.filter { it.role == EmbeddedWalletContent.AssetRole.ATTACHMENT }.map {
        NativeApiTokenAttachment(it.name, it.displayName, it.mimeType, it.size, it.sha256)
    }
    NativeApiTokenAttachmentList(attachments, onRead = { asset, output -> access.copyTo(asset.id, output) })
}
