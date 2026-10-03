package takagi.ru.monica.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.SettingsSubpageTopBar
import takagi.ru.monica.utils.ClipboardUtils

internal data class DeveloperLogsState(
    val snapshot: DeveloperLogSnapshot = DeveloperLogSnapshot(),
    val loading: Boolean = true,
    val error: String? = null,
)

internal class DeveloperLogsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(DeveloperLogsState())
    val state = mutableState.asStateFlow()
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    init { load(clear = false) }
    fun refresh() { if (!state.value.loading) load(clear = false) }
    fun clear() { if (!state.value.loading) load(clear = true) }
    private fun load(clear: Boolean) {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                if (clear) {
                    val result = DeveloperLogDebugHelper.clearLogs(context)
                    notices.send(context.getString(if (result.logcatCleared) R.string.developer_log_buffer_cleared
                        else R.string.developer_clear_failed, result.reason.orEmpty()))
                }
                mutableState.value = DeveloperLogsState(DeveloperLogDebugHelper.collectLogs(context), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }
}

@Composable
fun DeveloperLogsScreen(onNavigateBack: () -> Unit) {
    val model: DeveloperLogsViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    DeveloperLogsContent(state, onNavigateBack, model::refresh, model::clear,
        sharing = sharing, snackbar = snackbar,
        onShare = {
            scope.launch {
                sharing = true
                try {
                    val intent = DeveloperLogDebugHelper.createShareIntent(context, state.snapshot.report)
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.developer_share_title)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    snackbar.showSnackbar(context.getString(R.string.developer_share_failed, error.message.orEmpty()))
                } finally { sharing = false }
            }
        },
        onCopy = { event ->
            // Keep large forensics payloads out of the clipboard Binder transaction.
            val copied = event.text.length <= 200_000 && runCatching {
                ClipboardUtils.copyToClipboard(context, event.text,
                    context.getString(R.string.developer_logs_title), sensitive = true)
            }.isSuccess
            scope.launch { snackbar.showSnackbar(context.getString(
                if (copied) R.string.copied_to_clipboard else R.string.developer_logs_copy_failed)) }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperLogsContent(
    state: DeveloperLogsState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onCopy: (DeveloperLogEvent) -> Unit,
    sharing: Boolean = false,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(DeveloperLogFilter.ALL) }
    var source by rememberSaveable { mutableStateOf<DeveloperLogSource?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val events = state.snapshot.events
    // Parsing and filtering large diagnostic files never run in the composition/layout thread.
    val filtered by produceState<Pair<List<DeveloperLogEvent>, List<DeveloperLogTextBlock>>?>(null, events, query, filter, source) {
        value = null
        value = withContext(Dispatchers.Default) {
            val matches = filterDeveloperLogEvents(events, query, filter, source)
            matches to developerLogTextBlocks(matches)
        }
    }
    val visible = filtered?.first
    val blocks = filtered?.second.orEmpty()
    val counts = remember(events, source) {
        val scoped = events.filter { source == null || it.source == source }
        mapOf(DeveloperLogFilter.ALL to scoped.size,
            DeveloperLogFilter.ERROR to scoped.count { it.level == DeveloperLogLevel.ERROR },
            DeveloperLogFilter.WARNING to scoped.count { it.level == DeveloperLogLevel.WARN })
    }
    Scaffold(modifier = Modifier.testTag("developer_logs_screen"),
        topBar = {
            SettingsSubpageTopBar(stringResource(R.string.developer_logs_title), onNavigateBack) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("logs_more")) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                    }
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.developer_clear_log_buffer)) },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            enabled = !state.loading,
                            onClick = { menu = false; confirmClear = true })
                    }
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)) {
                    val stacked = maxWidth < 380.dp && LocalDensity.current.fontScale > 1.2f
                    val refreshAction: @Composable (Modifier) -> Unit = { modifier ->
                        FilledTonalButton(onClick = onRefresh, enabled = !state.loading,
                            contentPadding = PaddingValues(horizontal = 16.dp), modifier = modifier.testTag("logs_refresh")) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.developer_refresh))
                        }
                    }
                    val shareAction: @Composable (Modifier) -> Unit = { modifier ->
                        Button(onClick = onShare, enabled = state.snapshot.report.isNotBlank() && !sharing && !state.loading,
                            modifier = modifier.testTag("logs_share")) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.developer_logs_share_full))
                        }
                    }
                    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        refreshAction(Modifier.fillMaxWidth())
                        shareAction(Modifier.fillMaxWidth())
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        refreshAction(Modifier)
                        shareAction(Modifier.weight(1f))
                    }
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("logs_list"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Top) {
            item("search") {
                TextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("logs_search"),
                    placeholder = { Text(stringResource(R.string.developer_logs_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear))
                    } }, singleLine = true, shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent))
            }
            item("filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeveloperLogFilter.entries.forEach { value ->
                        FilterChip(selected = filter == value, onClick = { filter = value },
                            modifier = Modifier.testTag("logs_filter_${value.name}"),
                            label = { Text("${stringResource(value.label())} ${counts[value] ?: 0}") },
                            leadingIcon = { Icon(when (value) {
                                DeveloperLogFilter.ALL -> Icons.Default.List
                                DeveloperLogFilter.ERROR -> Icons.Default.ErrorOutline
                                DeveloperLogFilter.WARNING -> Icons.Default.WarningAmber
                            }, null, Modifier.size(18.dp)) })
                    }
                }
            }
            item("sources") {
                Box {
                    Surface(onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth().testTag("logs_source"),
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.FilterList, null)
                            Text(stringResource(R.string.developer_logs_source, sourceLabel(source)), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                    DropdownMenu(sourceMenu, { sourceMenu = false }, shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        (listOf<DeveloperLogSource?>(null) + DeveloperLogSource.entries).forEach { value ->
                            DropdownMenuItem(text = { Text(sourceLabel(value)) },
                                modifier = Modifier.testTag("logs_source_${value?.name ?: "ALL"}"),
                                trailingIcon = { if (source == value) Icon(Icons.Default.Check, null) },
                                onClick = { source = value; sourceMenu = false })
                        }
                    }
                }
            }
            item("hint") {
                Text(stringResource(R.string.developer_logs_hint), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading || visible == null) item("loading") {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.developer_loading_logs), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.error?.let { error -> item("error") {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.developer_load_failed, error))
                        TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.retry)) }
                    }
                }
            } }
            if (state.snapshot.collectedAt != 0L) item("count") {
                Text(stringResource(R.string.developer_logs_result_count, visible?.size ?: 0, events.size,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.snapshot.collectedAt))),
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.loading && state.error == null && visible?.isEmpty() == true) item("empty") {
                Text(stringResource(if (events.isEmpty()) R.string.developer_no_logs else R.string.developer_logs_no_matches),
                    Modifier.fillMaxWidth().padding(20.dp).testTag("logs_empty"), style = MaterialTheme.typography.bodyMedium)
            }
            items(blocks, key = { it.id }, contentType = { "log_text" }) { block ->
                Text(block.text.removeSuffix("\n").removeSuffix("\r"),
                    modifier = Modifier.fillMaxWidth().testTag("log_event_${block.id}")
                        .combinedClickable(onClick = {}, onLongClick = { onCopy(block.event) },
                            onLongClickLabel = stringResource(R.string.developer_logs_copy_event)),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = when (block.event.level) {
                        DeveloperLogLevel.ERROR -> MaterialTheme.colorScheme.error
                        DeveloperLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    })
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        icon = { Icon(Icons.Default.DeleteSweep, null) },
        title = { Text(stringResource(R.string.developer_clear_log_buffer)) },
        text = { Text(stringResource(R.string.developer_logs_clear_confirmation)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, enabled = !state.loading,
            modifier = Modifier.testTag("logs_confirm_clear")) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.testTag("logs_cancel_clear")) {
            Text(stringResource(R.string.cancel))
        } })
}

private fun DeveloperLogFilter.label() = when (this) {
    DeveloperLogFilter.ALL -> R.string.developer_filter_all
    DeveloperLogFilter.ERROR -> R.string.developer_filter_errors
    DeveloperLogFilter.WARNING -> R.string.developer_filter_warnings
}
@Composable
private fun sourceLabel(source: DeveloperLogSource?): String = when (source) {
    null -> stringResource(R.string.developer_filter_all)
    DeveloperLogSource.SYSTEM -> stringResource(R.string.developer_system_logs)
    DeveloperLogSource.AUTOFILL -> stringResource(R.string.autofill)
    DeveloperLogSource.BITWARDEN -> "Bitwarden"
    DeveloperLogSource.FORENSICS -> stringResource(R.string.developer_forensics_group)
    DeveloperLogSource.MDBX -> "MDBX"
    DeveloperLogSource.SECURITY -> stringResource(R.string.developer_logs_security)
    DeveloperLogSource.STEAM -> "Steam"
    DeveloperLogSource.PASSKEY -> stringResource(R.string.passkey)
}
