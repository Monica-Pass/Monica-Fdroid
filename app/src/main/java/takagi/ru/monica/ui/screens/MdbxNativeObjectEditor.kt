package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import takagi.ru.monica.R
import takagi.ru.monica.data.NativeApiTokenUpload
import takagi.ru.monica.repository.MdbxNativeAttachment
import takagi.ru.monica.repository.MdbxNativeObjectDetail

/** Edit only the known login fields; opaque extension fields survive a save. */
internal fun mdbxNativeLoginPayload(original: String?, username: String, password: String, website: String, notes: String): String {
    val fields = original?.let { Json.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
    fields["username"] = JsonPrimitive(username)
    fields["password_plain"] = JsonPrimitive(password)
    fields["website"] = JsonPrimitive(website)
    fields["notes"] = JsonPrimitive(notes)
    fields["monica_password_encoding"] = JsonPrimitive("plaintext-v1")
    return JsonObject(fields).toString()
}

private data class PickedNativeAttachment(val uri: Uri, val upload: NativeApiTokenUpload)

/** Drafts deliberately use remember, never saved state, because the parent removes this editor at lock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MdbxNativeObjectEditor(
    original: MdbxNativeObjectDetail?,
    selectedUris: List<Uri>,
    busy: Boolean,
    failure: String?,
    initialDraft: AddEditPasswordInitialDraft? = null,
    targetLabel: String? = null,
    onPickAttachments: () -> Unit,
    onRemoveUpload: (Uri) -> Unit,
    onBack: () -> Unit,
    onSave: (String, String, String, List<NativeApiTokenUpload>, Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val fields = remember(original) { original?.payload?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } }
    fun field(key: String): String = (fields?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val login = original == null || (original.summary.type == "login" && fields?.containsKey("password_plain") == true)
    var title by remember(original) { mutableStateOf(original?.summary?.title ?: initialDraft?.title.orEmpty()) }
    var username by remember(original) { mutableStateOf(if (original == null) initialDraft?.username.orEmpty() else field("username")) }
    var password by remember(original) { mutableStateOf(if (original == null) initialDraft?.password.orEmpty() else field("password_plain")) }
    var website by remember(original) { mutableStateOf(if (original == null) initialDraft?.website.orEmpty() else field("website")) }
    var notes by remember(original) { mutableStateOf(field("notes")) }
    var rawJson by remember(original) { mutableStateOf(original?.payload.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var removed by remember(original) { mutableStateOf(emptySet<String>()) }
    var uploads by remember { mutableStateOf(emptyList<PickedNativeAttachment>()) }
    var readingUploads by remember { mutableStateOf(false) }
    var uploadFailure by remember { mutableStateOf(false) }
    LaunchedEffect(selectedUris) {
        readingUploads = true
        uploadFailure = false
        try {
            uploads = withContext(Dispatchers.IO) {
                selectedUris.map { uri ->
                    val resolver = context.contentResolver
                    var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "attachment" }
                    var size: Long? = null
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                        }
                    }
                    require(size == null || size!! <= 16L * 1024 * 1024)
                    PickedNativeAttachment(uri, NativeApiTokenUpload(name, resolver.getType(uri) ?: "application/octet-stream", size) {
                        requireNotNull(resolver.openInputStream(uri)) { "Cannot open selected attachment." }
                    })
                }.also { files -> require(files.sumOf { it.upload.expectedSize ?: 0L } <= 16L * 1024 * 1024) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { uploads = emptyList(); uploadFailure = true }
        finally { readingUploads = false }
    }
    val jsonValid = login || runCatching { Json.parseToJsonElement(rawJson) is JsonObject }.getOrDefault(false)
    val attachmentCount = original?.attachmentRecords.orEmpty().size - removed.size + selectedUris.size
    val saveEnabled = !busy && !readingUploads && !uploadFailure && jsonValid &&
        title.isNotBlank() && title.length <= 512 && attachmentCount <= 16
    Scaffold(
        topBar = {
            MdbxTopAppBar(title = { Text(stringResource(if (original == null) R.string.mdbx_native_add_login else R.string.edit)) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                } })
        },
        bottomBar = {
            MdbxFormActionBar {
                Button(onClick = {
                    val seedPayload = original?.payload ?: initialDraft?.let { draft -> buildJsonObject {
                        if (draft.appPackageName.isNotBlank()) put("app_package_name", draft.appPackageName)
                        if (draft.appName.isNotBlank()) put("app_name", draft.appName)
                    }.toString() }
                    val payload = if (login) mdbxNativeLoginPayload(seedPayload, username, password, website, notes) else rawJson
                    onSave(title.trim(), original?.summary?.type ?: "login", payload, uploads.map { it.upload }, removed)
                }, enabled = saveEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native_editor_save")) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.save))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            targetLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("native_editor_target")) }
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("native_editor_title"), shape = MdbxFieldShape)
            if (login) {
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.mdbx_native_username)) },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("native_editor_username"), shape = MdbxFieldShape)
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.mdbx_native_password)) },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("native_editor_password"), shape = MdbxFieldShape,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password))
                    } })
                OutlinedTextField(website, { website = it }, label = { Text(stringResource(R.string.mdbx_native_website)) },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MdbxFieldShape)
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.mdbx_native_notes)) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = MdbxFieldShape)
            } else {
                Text(stringResource(R.string.mdbx_native_json_description), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(rawJson, { rawJson = it }, label = { Text("JSON") }, enabled = !busy, isError = !jsonValid,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("native_editor_json"), minLines = 8, shape = MdbxFieldShape)
            }
            Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleMedium)
            original?.attachmentRecords.orEmpty().filterNot { it.id in removed }.forEach { attachment ->
                ListItem(headlineContent = { Text(attachment.fileName) }, supportingContent = { Text("${attachment.size} B") },
                    trailingContent = { IconButton(onClick = { removed = removed + attachment.id }, enabled = !busy) {
                        Icon(Icons.Default.Close, stringResource(R.string.delete))
                    } })
            }
            uploads.forEach { picked ->
                ListItem(headlineContent = { Text(picked.upload.fileName) }, trailingContent = {
                    IconButton(onClick = { onRemoveUpload(picked.uri) }, enabled = !busy) {
                        Icon(Icons.Default.Close, stringResource(R.string.delete))
                    }
                })
            }
            if (uploadFailure) selectedUris.forEach { uri ->
                ListItem(headlineContent = { Text(uri.lastPathSegment?.substringAfterLast('/').orEmpty()) },
                    trailingContent = { IconButton(onClick = { onRemoveUpload(uri) }, enabled = !busy) {
                        Icon(Icons.Default.Close, stringResource(R.string.delete))
                    } })
            }
            OutlinedButton(onClick = onPickAttachments, enabled = !busy && attachmentCount < 16,
                modifier = Modifier.fillMaxWidth().testTag("native_editor_add_attachment")) {
                Text(stringResource(R.string.mdbx_native_add_attachment))
            }
            Text(stringResource(R.string.mdbx_native_attachment_limits), style = MaterialTheme.typography.bodySmall)
            if (uploadFailure) Text(stringResource(R.string.mdbx_native_attachment_failed), color = MaterialTheme.colorScheme.error)
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private data class NativeAttachmentPreview(val text: String? = null, val bitmap: Bitmap? = null)

@Composable
internal fun MdbxNativeAttachmentPreview(
    attachment: MdbxNativeAttachment,
    load: suspend () -> ByteArray,
    onDismiss: () -> Unit,
) {
    var preview by remember(attachment.id) { mutableStateOf<NativeAttachmentPreview?>(null) }
    var failed by remember(attachment.id) { mutableStateOf(false) }
    LaunchedEffect(attachment.id) {
        try {
            val bytes = load()
            var decodedBitmap: Bitmap? = null
            try {
                preview = withContext(Dispatchers.Default) {
                    when {
                        attachment.mimeType?.startsWith("text/") == true -> NativeAttachmentPreview(text = bytes.copyOfRange(0, minOf(bytes.size, 256 * 1024)).let {
                            try { it.toString(Charsets.UTF_8) } finally { it.fill(0) }
                        })
                        attachment.mimeType?.startsWith("image/") == true -> {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0)
                            var sample = 1
                            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inSampleSize = sample }))
                            decodedBitmap = bitmap
                            NativeAttachmentPreview(bitmap = bitmap)
                        }
                        else -> NativeAttachmentPreview()
                    }
                }
                decodedBitmap = null // The displayed preview now owns this bitmap.
            } finally { bytes.fill(0); decodedBitmap?.recycle() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val renderedPreview = preview
    DisposableEffect(renderedPreview) { onDispose { renderedPreview?.bitmap?.recycle() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(attachment.fileName) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                failed -> Text(stringResource(R.string.mdbx_native_attachment_failed))
                preview == null -> CircularProgressIndicator()
                else -> {
                    Text(stringResource(R.string.mdbx_native_attachment_verified), style = MaterialTheme.typography.labelMedium)
                    Text("SHA-256 ${attachment.sha256}", style = MaterialTheme.typography.bodySmall)
                    preview?.bitmap?.let { Image(it.asImageBitmap(), attachment.fileName, Modifier.fillMaxWidth()) }
                    preview?.text?.let { Text(it) }
                    if (preview?.text == null && preview?.bitmap == null) Text(stringResource(R.string.mdbx_native_attachment_no_preview))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
