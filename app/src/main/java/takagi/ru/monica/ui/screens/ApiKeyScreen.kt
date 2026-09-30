package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.ApiKeyEntryFields
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.viewmodel.ApiKeyEditorViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyScreen(
    passwords: PasswordViewModel,
    onBack: () -> Unit,
    passwordId: Long? = null,
    initialTarget: StorageTarget = StorageTarget.MonicaLocal(null),
    onSaved: ((Long?) -> Unit)? = null,
    onSelectType: ((EntryTypeChipOption) -> Unit)? = null,
    getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>> = { flowOf(emptyList()) },
    editor: ApiKeyEditorViewModel = viewModel(key = "api-key:${passwordId ?: "new"}"),
) {
    androidx.compose.runtime.CompositionLocalProvider(takagi.ru.monica.ui.components.LocalTemplateTargets provides
        (takagi.ru.monica.ui.components.LocalTemplateTargets.current ?: listOf(initialTarget))) {
        AddEditPasswordScreen(viewModel = passwords, passwordId = passwordId, initialLoginType = "API_KEY",
            onNavigateBack = onBack, onSaveCompleted = onSaved,
            onSwitchToWifi = { onSelectType?.invoke(EntryTypeChipOption.WIFI) },
            onSwitchToSshKey = { onSelectType?.invoke(EntryTypeChipOption.SSH_KEY) },
            onSwitchToApiToken = { onSelectType?.invoke(EntryTypeChipOption.API_TOKEN) })
    }
}

@Composable
internal fun ApiKeyDetailContent(
    entry: PasswordEntry,
    secret: String?,
    fields: List<CustomField>,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val apiUrl = fields.firstOrNull { it.title == ApiKeyEntryFields.API_URL }?.value.orEmpty()
    Column(modifier.then(if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 96.dp)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(entry.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("api_key_detail_title"))
        Text(stringResource(R.string.api_key_title), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        if (entry.website.isNotBlank()) ApiKeyDetailField(stringResource(R.string.api_key_website_label),
            entry.website, Icons.Default.Language, "api_key_detail_website", openUrl = true)
        if (secret == null) Text(stringResource(R.string.api_key_load_error), color = MaterialTheme.colorScheme.error)
        else ApiKeyDetailField(stringResource(R.string.api_key_title), secret, Icons.Default.VpnKey,
            "api_key_detail_secret", sensitive = true)
        if (apiUrl.isNotBlank()) ApiKeyDetailField(stringResource(R.string.api_key_url_label),
            apiUrl, Icons.Default.Link, "api_key_detail_url")
        if (!embedded && entry.notes.isNotBlank()) ApiKeyDetailField(stringResource(R.string.notes), entry.notes,
            Icons.Default.Notes, "api_key_detail_notes")
        fields.filterNot { embedded || ApiKeyEntryFields.owns(it.title) }.forEach { field ->
            ApiKeyDetailField(field.title, field.value, Icons.Default.TextFields, "api_key_extra_${field.id}",
                sensitive = field.isProtected)
        }
    }
}

@Composable
private fun ApiKeyDetailField(
    label: String, value: String, icon: ImageVector, tag: String,
    sensitive: Boolean = false, openUrl: Boolean = false,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var visible by remember(value) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) visible = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { SessionManager.isUnlocked.drop(1).collect { if (!it) visible = false } }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                if (sensitive) IconButton(onClick = { visible = !visible }, modifier = Modifier.testTag("${tag}_reveal")) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (visible) R.string.api_key_hide else R.string.api_key_show))
                }
                IconButton(onClick = { ClipboardUtils.copyToClipboard(context, value, label, sensitive = sensitive) },
                    modifier = Modifier.testTag("${tag}_copy")) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.copy))
                }
            }
            if (sensitive && !visible) Text("••••••••••••••••", modifier = Modifier.testTag(tag))
            else SelectionContainer {
                Text(value, fontFamily = if (sensitive) FontFamily.Monospace else FontFamily.Default,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag))
            }
            if (openUrl && ApiKeyEntryFields.isValidOptionalUrl(value)) TextButton(onClick = {
                runCatching { uriHandler.openUri(value) }
            }) { Text(stringResource(R.string.api_key_open_website)) }
        }
    }
}
