package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.MdbxUnknownEntry
import takagi.ru.monica.security.SecurityManager

@Composable
internal fun MdbxUnknownEntryScreen(entry: PasswordEntry, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var payload by remember(entry.id) { mutableStateOf<String?>(null) }
    var failed by remember(entry.id) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!foreground) payload = null
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(entry.id, foreground) {
        if (!foreground) return@LaunchedEffect
        failed = false
        try {
            payload = withContext(Dispatchers.IO) {
                val repository = Mdbx2Repository(context,
                    PasswordDatabase.getDatabase(context).localMdbxDatabaseDao(), SecurityManager(context))
                repository.readUnknownEntry(requireNotNull(entry.mdbxDatabaseId), requireNotNull(entry.replicaGroupId)).payloadJson
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    MdbxUnknownEntryContent(entry.title, entry.loginType.removePrefix(MdbxUnknownEntry.LOGIN_TYPE_PREFIX),
        payload, failed, onBack)
}

/** Retains nested JSON and null/boolean/number values; no schema assumptions or secret previews. */
internal fun mdbxUnknownFields(payload: String): List<Pair<String, String>> {
    val parsed = runCatching { Json.parseToJsonElement(payload) }.getOrNull()
    val pretty = Json { prettyPrint = true }
    fun display(value: JsonElement): String =
        if (value is JsonPrimitive && value.isString) value.content
        else pretty.encodeToString(JsonElement.serializer(), value)
    return if (parsed is JsonObject && parsed.isNotEmpty()) parsed.map { (key, value) -> key to display(value) }
    else listOf("JSON" to (parsed?.let(::display) ?: payload))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MdbxUnknownEntryContent(title: String, type: String, payload: String?, failed: Boolean, onBack: () -> Unit,
    notice: String = stringResource(R.string.mdbx_unknown_read_only), allowSelection: Boolean = true,
    metadataContent: @Composable () -> Unit = {}) {
    val fields = remember(payload) { payload?.let(::mdbxUnknownFields).orEmpty() }
    val visible = remember(payload) { mutableStateMapOf<Int, Boolean>() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("mdbx-unknown-detail"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(type, style = MaterialTheme.typography.titleMedium)
                        Text(notice, style = MaterialTheme.typography.bodyMedium)
                        metadataContent()
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (failed) item { Text(stringResource(R.string.mdbx_unknown_unavailable)) }
            else if (payload == null) item { CircularProgressIndicator() }
            itemsIndexed(fields) { index, (label, value) ->
                Surface(shape = RoundedCornerShape(topStart = if (index == 0) 24.dp else 4.dp,
                    topEnd = if (index == 0) 24.dp else 4.dp,
                    bottomStart = if (index == fields.lastIndex) 24.dp else 4.dp,
                    bottomEnd = if (index == fields.lastIndex) 24.dp else 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            if (visible[index] == true) {
                                if (allowSelection) SelectionContainer { Text(value, Modifier.testTag("mdbx-field-value-$index")) }
                                else Text(value, Modifier.testTag("mdbx-field-value-$index"))
                            }
                            else Text("••••••••", Modifier.testTag("mdbx-field-hidden-$index"))
                        }
                        IconButton(onClick = { visible[index] = visible[index] != true },
                            modifier = Modifier.testTag("mdbx-field-toggle-$index")) {
                            Icon(if (visible[index] == true) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (visible[index] == true) R.string.mdbx_unknown_hide else R.string.mdbx_unknown_show))
                        }
                    }
                }
            }
        }
    }
}
