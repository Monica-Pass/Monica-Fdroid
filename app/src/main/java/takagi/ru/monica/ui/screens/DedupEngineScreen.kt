package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.dedup.DedupConflictPolicy
import takagi.ru.monica.data.dedup.DedupMergeExecutionResult
import takagi.ru.monica.data.dedup.DedupMergePlan
import takagi.ru.monica.data.dedup.DedupMergeSourceKind
import takagi.ru.monica.data.dedup.DedupMergeSourceOption
import takagi.ru.monica.data.dedup.DedupMergeTarget
import takagi.ru.monica.data.dedup.DedupMergeTargetOption
import takagi.ru.monica.data.dedup.DedupResolvedPassword
import takagi.ru.monica.data.dedup.DedupResolvedSecureItem
import takagi.ru.monica.data.dedup.dedupLabel
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.viewmodel.DedupEngineUiState

private enum class DedupSheet {
    SOURCES,
    TARGET,
    PREVIEW,
    WARNINGS,
    FAILURES
}

private enum class DedupPreviewFilter {
    ALL,
    WRITE,
    CONFLICT,
    SKIP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupEngineScreen(
    uiState: DedupEngineUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSource: (String) -> Unit,
    onSelectAllSources: () -> Unit,
    onClearSources: () -> Unit,
    onSelectTarget: (DedupMergeTarget) -> Unit,
    onCreateMdbxTarget: () -> Unit,
    onConflictPolicyChange: (DedupConflictPolicy) -> Unit,
    onExecuteMerge: () -> Unit,
    onCancelMerge: () -> Unit,
    onConsumeMessage: () -> Unit
) {
    val strings = rememberScreenStrings()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMergeConfirmation by rememberSaveable { mutableStateOf(false) }
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var activeSheet by rememberSaveable { mutableStateOf<DedupSheet?>(null) }
    var previewFilter by rememberSaveable { mutableStateOf(DedupPreviewFilter.ALL) }
    val busy = uiState.isLoading || uiState.isAnalyzing || uiState.isExecutingMerge

    fun requestBack() {
        if (uiState.isExecutingMerge) showCancelConfirmation = true else onNavigateBack()
    }

    BackHandler(enabled = uiState.isExecutingMerge) { showCancelConfirmation = true }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onConsumeMessage()
    }
    LaunchedEffect(uiState.isExecutingMerge) {
        if (uiState.isExecutingMerge) {
            showMergeConfirmation = false
            activeSheet = null
        }
    }

    when (activeSheet) {
        DedupSheet.SOURCES -> SourceSelectionSheet(
            sources = uiState.sourceOptions,
            selectedKeys = uiState.selectedMergeSourceKeys,
            targetSourceKey = uiState.selectedTargetOption?.sourceKey,
            onToggleSource = onToggleSource,
            onSelectAllSources = onSelectAllSources,
            onClearSources = onClearSources,
            onDismiss = { activeSheet = null }
        )
        DedupSheet.TARGET -> TargetSelectionSheet(
            targets = uiState.targetOptions,
            selectedTarget = uiState.selectedMergeTarget,
            selectedSourceKeys = uiState.selectedMergeSourceKeys,
            onSelectTarget = {
                onSelectTarget(it)
                activeSheet = null
            },
            onCreateMdbxTarget = {
                activeSheet = null
                onCreateMdbxTarget()
            },
            onDismiss = { activeSheet = null }
        )
        DedupSheet.PREVIEW -> MergePreviewSheet(
            plan = uiState.mergePlan,
            selectedFilter = previewFilter,
            onFilterSelected = { previewFilter = it },
            onDismiss = { activeSheet = null }
        )
        DedupSheet.WARNINGS -> WarningSheet(
            warnings = uiState.mergePlan.warnings,
            onDismiss = { activeSheet = null }
        )
        DedupSheet.FAILURES -> uiState.executionResult?.let { result ->
            FailureSheet(result = result, onDismiss = { activeSheet = null })
        }
        null -> Unit
    }

    if (showMergeConfirmation) {
        MergeConfirmationDialog(
            uiState = uiState,
            onDismiss = { showMergeConfirmation = false },
            onConfirm = {
                showMergeConfirmation = false
                onExecuteMerge()
            }
        )
    }
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(strings.get(R.string.dedup_merge_stop_title)) },
            text = { Text(strings.get(R.string.dedup_merge_stop_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirmation = false
                        onCancelMerge()
                    }
                ) { Text(strings.get(R.string.dedup_merge_stop_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) { Text(strings.get(R.string.dedup_merge_continue_action)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.get(R.string.dedup_merge_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.get(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.get(R.string.dedup_merge_refresh))
                    }
                }
            )
        },
        bottomBar = {
            MergeBottomBar(
                uiState = uiState,
                onReviewAndMerge = { showMergeConfirmation = true }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.isLoading) {
                item(key = "loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            item(key = "setup") {
                CompactSetupPanel(
                    sources = uiState.sourceOptions,
                    selectedSourceKeys = uiState.selectedMergeSourceKeys,
                    selectedTarget = uiState.selectedTargetOption,
                    enabled = !uiState.isExecutingMerge,
                    onOpenSources = { activeSheet = DedupSheet.SOURCES },
                    onOpenTarget = { activeSheet = DedupSheet.TARGET }
                )
            }
            item(key = "policy") {
                ConflictPolicyPanel(
                    selected = uiState.conflictPolicy,
                    enabled = !busy,
                    onSelected = onConflictPolicyChange
                )
            }

            item(key = "summary") {
                MergeSummaryPanel(uiState)
            }

            if (uiState.mergePlan.warnings.isNotEmpty()) {
                item(key = "warnings") {
                    CompactLinkPanel(
                        icon = Icons.Default.Warning,
                        title = strings.get(R.string.dedup_merge_warnings_title),
                        subtitle = strings.get(R.string.dedup_merge_warning_count, uiState.mergePlan.warnings.size),
                        tint = MaterialTheme.colorScheme.tertiary,
                        onClick = { activeSheet = DedupSheet.WARNINGS }
                    )
                }
            }
            uiState.error?.let { error ->
                item(key = "error") {
                    MessagePanel(Icons.Default.Error, MaterialTheme.colorScheme.error, error)
                }
            }
            uiState.executionProgress?.let { progress ->
                item(key = "progress") {
                    ExecutionProgressPanel(
                        completed = progress.completedItems,
                        total = progress.totalItems,
                        currentLabel = progress.currentLabel,
                        fraction = progress.fraction,
                        onCancel = { showCancelConfirmation = true }
                    )
                }
            }
            uiState.executionResult?.let { result ->
                item(key = "result") {
                    ExecutionResultPanel(
                        result = result,
                        onViewFailures = { activeSheet = DedupSheet.FAILURES }
                    )
                }
            }

            if (uiState.selection.validate(uiState.mergePlan.writableItems).canReview) {
                item(key = "preview") {
                    CompactLinkPanel(
                        icon = Icons.Default.Merge,
                        title = strings.get(R.string.dedup_merge_preview_title),
                        subtitle = strings.get(
                            R.string.dedup_merge_preview_summary,
                            uiState.mergePlan.previewPasswords.size + uiState.mergePlan.previewSecureItems.size,
                            uiState.mergePlan.conflictGroupsTotal
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { activeSheet = DedupSheet.PREVIEW }
                    )
                }
            } else if (!uiState.isLoading) {
                item(key = "preview_empty") {
                    MessagePanel(
                        icon = Icons.Default.Info,
                        tint = MaterialTheme.colorScheme.primary,
                        text = when {
                            uiState.selectedMergeSourceKeys.size < 2 -> strings.get(R.string.dedup_merge_preview_need_sources)
                            uiState.selectedMergeTarget == null -> strings.get(R.string.dedup_merge_preview_need_target)
                            else -> strings.get(R.string.dedup_merge_preview_empty)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSetupPanel(
    sources: List<DedupMergeSourceOption>,
    selectedSourceKeys: Set<String>,
    selectedTarget: DedupMergeTargetOption?,
    enabled: Boolean,
    onOpenSources: () -> Unit,
    onOpenTarget: () -> Unit
) {
    val strings = rememberScreenStrings()
    val selectedSources = sources.filter { it.key in selectedSourceKeys }
    val sourceSummary = when {
        selectedSources.isEmpty() -> strings.get(R.string.dedup_merge_source_empty)
        selectedSources.size <= 2 -> selectedSources.joinToString(strings.get(R.string.dedup_merge_list_separator)) { it.label }
        else -> strings.get(
            R.string.dedup_merge_source_summary_more,
            selectedSources.take(2).joinToString(strings.get(R.string.dedup_merge_list_separator)) { it.label },
            selectedSources.size
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            CompactSelectionRow(
                index = 1,
                title = strings.get(R.string.dedup_merge_source_title),
                subtitle = sourceSummary,
                complete = selectedSources.size >= 2,
                enabled = enabled,
                onClick = onOpenSources
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            CompactSelectionRow(
                index = 2,
                title = strings.get(R.string.dedup_merge_target_title),
                subtitle = selectedTarget?.let { "${it.label} · ${it.countSummary(strings)}" } ?: strings.get(R.string.dedup_merge_target_empty),
                complete = selectedTarget != null,
                enabled = enabled,
                onClick = onOpenTarget
            )
        }
    }
}

@Composable
private fun CompactSelectionRow(
    index: Int,
    title: String,
    subtitle: String,
    complete: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val strings = rememberScreenStrings()
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = if (complete) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (complete) {
                        Icon(Icons.Default.Check, contentDescription = strings.get(R.string.qs_completed), modifier = Modifier.size(19.dp))
                    } else {
                        Text(index.toString(), fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        trailingContent = {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    )
}

@Composable
private fun CompactLinkPanel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
            trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSelectionSheet(
    sources: List<DedupMergeSourceOption>,
    selectedKeys: Set<String>,
    targetSourceKey: String?,
    onToggleSource: (String) -> Unit,
    onSelectAllSources: () -> Unit,
    onClearSources: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = rememberScreenStrings()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
        ) {
            SheetHeader(strings.get(R.string.dedup_merge_source_title), strings.get(R.string.dedup_merge_selected_count, selectedKeys.size), onDismiss)
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = onSelectAllSources) { Text(strings.get(R.string.dedup_merge_select_all)) }
                TextButton(onClick = onClearSources, enabled = selectedKeys.isNotEmpty()) { Text(strings.get(R.string.clear)) }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (sources.isEmpty()) {
                    item { SheetEmptyText(strings.get(R.string.dedup_merge_no_sources)) }
                }
                items(sources, key = { it.key }) { source ->
                    ListItem(
                        modifier = Modifier
                            .semantics { selected = source.key in selectedKeys }
                            .clickable(role = Role.Checkbox) { onToggleSource(source.key) },
                        headlineContent = { Text(source.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (source.key == targetSourceKey) {
                                    strings.get(R.string.dedup_merge_source_is_target)
                                } else {
                                    "${source.kind.label()} · ${source.countSummary(strings)}"
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            Checkbox(
                                checked = source.key in selectedKeys,
                                onCheckedChange = { onToggleSource(source.key) }
                            )
                        },
                        trailingContent = {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = sourceColor(source.kind))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelectionSheet(
    targets: List<DedupMergeTargetOption>,
    selectedTarget: DedupMergeTarget?,
    selectedSourceKeys: Set<String>,
    onSelectTarget: (DedupMergeTarget) -> Unit,
    onCreateMdbxTarget: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = rememberScreenStrings()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding()
        ) {
            SheetHeader(strings.get(R.string.dedup_merge_target_title), strings.get(R.string.dedup_merge_target_add_only), onDismiss)
            Text(
                strings.get(R.string.dedup_merge_target_support),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(targets, key = { it.sourceKey }) { target ->
                    ListItem(
                        modifier = Modifier
                            .semantics { selected = target.target == selectedTarget }
                            .clickable(role = Role.RadioButton) { onSelectTarget(target.target) },
                        headlineContent = { Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (target.sourceKey in selectedSourceKeys) {
                                    strings.get(R.string.dedup_merge_target_is_source, target.countSummary(strings))
                                } else {
                                    target.countSummary(strings)
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = target.target == selectedTarget,
                                onClick = { onSelectTarget(target.target) }
                            )
                        }
                    )
                }
            }
            OutlinedButton(
                onClick = onCreateMdbxTarget,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.get(R.string.dedup_merge_create_target))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergePreviewSheet(
    plan: DedupMergePlan,
    selectedFilter: DedupPreviewFilter,
    onFilterSelected: (DedupPreviewFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = rememberScreenStrings()
    val passwords = plan.previewPasswords.filter { selectedFilter.matches(it.existsInTarget, it.conflictFields) }
    val secureItems = plan.previewSecureItems.filter { selectedFilter.matches(it.existsInTarget, it.conflictFields) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
        ) {
            SheetHeader(
                title = strings.get(R.string.dedup_merge_preview_title),
                subtitle = strings.get(R.string.dedup_merge_items_count, plan.previewPasswords.size + plan.previewSecureItems.size),
                onDismiss = onDismiss
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DedupPreviewFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { onFilterSelected(filter) },
                        label = { Text(filter.label(plan, strings)) }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (passwords.isEmpty() && secureItems.isEmpty()) {
                    item { SheetEmptyText(strings.get(R.string.dedup_merge_filter_empty)) }
                }
                items(passwords, key = { "password:${it.mergeKey}" }) { resolved ->
                    PasswordPreviewRow(resolved)
                }
                items(secureItems, key = { "secure:${it.mergeKey}" }) { resolved ->
                    SecureItemPreviewRow(resolved)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarningSheet(warnings: List<String>, onDismiss: () -> Unit) {
    val strings = rememberScreenStrings()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .navigationBarsPadding()
        ) {
            SheetHeader(strings.get(R.string.dedup_merge_warnings_title), strings.get(R.string.dedup_merge_record_count, warnings.size), onDismiss)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(warnings) { warning ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(warning, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FailureSheet(result: DedupMergeExecutionResult, onDismiss: () -> Unit) {
    val strings = rememberScreenStrings()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding()
        ) {
            SheetHeader(strings.get(R.string.dedup_merge_failures_title), strings.get(R.string.dedup_merge_record_count, result.failures.size), onDismiss)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(result.failures) { failure ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(failure.label, fontWeight = FontWeight.Medium)
                            Text(failure.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    val strings = rememberScreenStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDismiss) { Text(strings.get(R.string.dedup_merge_done)) }
    }
}

@Composable
private fun SheetEmptyText(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun DedupPreviewFilter.matches(existsInTarget: Boolean, conflictFields: Set<String>): Boolean = when (this) {
    DedupPreviewFilter.ALL -> true
    DedupPreviewFilter.WRITE -> !existsInTarget
    DedupPreviewFilter.CONFLICT -> conflictFields.isNotEmpty()
    DedupPreviewFilter.SKIP -> existsInTarget
}

private fun DedupPreviewFilter.label(plan: DedupMergePlan, strings: StringResolver): String = when (this) {
    DedupPreviewFilter.ALL -> strings.get(R.string.dedup_merge_filter_all, plan.previewPasswords.size + plan.previewSecureItems.size)
    DedupPreviewFilter.WRITE -> strings.get(R.string.dedup_merge_filter_write, plan.writableItems)
    DedupPreviewFilter.CONFLICT -> strings.get(R.string.dedup_merge_filter_conflict, plan.conflictGroupsTotal)
    DedupPreviewFilter.SKIP -> strings.get(R.string.dedup_merge_filter_skip, plan.targetExistingDuplicates + plan.targetExistingSecureItems)
}

@Composable
private fun ConflictPolicyPanel(
    selected: DedupConflictPolicy,
    enabled: Boolean,
    onSelected: (DedupConflictPolicy) -> Unit
) {
    val strings = rememberScreenStrings()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.get(R.string.dedup_merge_policy_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == DedupConflictPolicy.MOST_COMPLETE,
                onClick = { onSelected(DedupConflictPolicy.MOST_COMPLETE) },
                enabled = enabled,
                label = { Text(strings.get(R.string.dedup_merge_policy_complete)) },
                leadingIcon = if (selected == DedupConflictPolicy.MOST_COMPLETE) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null
            )
            FilterChip(
                selected = selected == DedupConflictPolicy.NEWEST,
                onClick = { onSelected(DedupConflictPolicy.NEWEST) },
                enabled = enabled,
                label = { Text(strings.get(R.string.dedup_merge_policy_newest)) },
                leadingIcon = if (selected == DedupConflictPolicy.NEWEST) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null
            )
        }
        Text(
            strings.get(R.string.dedup_merge_policy_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MergeSummaryPanel(uiState: DedupEngineUiState) {
    val strings = rememberScreenStrings()
    val plan = uiState.mergePlan
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(strings.get(R.string.dedup_merge_summary_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            uiState.isExecutingMerge -> strings.get(R.string.dedup_merge_writing_target)
                            uiState.isAnalyzing -> strings.get(R.string.dedup_merge_analyzing)
                            else -> uiState.selectedTargetOption?.label ?: strings.get(R.string.dedup_merge_target_empty)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (uiState.isAnalyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Merge, contentDescription = null)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPill(strings.get(R.string.dedup_merge_summary_sources), plan.totalSourceItems)
                SummaryPill(strings.get(R.string.dedup_merge_summary_write), plan.writableItems)
                SummaryPill(strings.get(R.string.dedup_merge_summary_existing), plan.targetExistingDuplicates + plan.targetExistingSecureItems)
                SummaryPill(strings.get(R.string.dedup_merge_summary_duplicates), plan.duplicateGroupsTotal)
                SummaryPill(strings.get(R.string.dedup_merge_summary_conflicts), plan.conflictGroupsTotal)
                if (plan.unsupportedSourcePasskeys > 0) SummaryPill(strings.get(R.string.dedup_merge_summary_unsupported), plan.unsupportedSourcePasskeys)
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, value: Int) {
    val strings = rememberScreenStrings()
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Text(
            strings.get(R.string.dedup_merge_label_count, label, value),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun MergeBottomBar(uiState: DedupEngineUiState, onReviewAndMerge: () -> Unit) {
    val strings = rememberScreenStrings()
    val validation = uiState.validation
    Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onReviewAndMerge,
                enabled = validation.canExecute && !uiState.isAnalyzing && !uiState.isLoading && !uiState.isExecutingMerge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        uiState.isExecutingMerge -> strings.get(R.string.dedup_merge_merging)
                        uiState.selectedMergeSourceKeys.size < 2 -> strings.get(R.string.dedup_merge_need_sources)
                        uiState.selectedMergeTarget == null -> strings.get(R.string.dedup_merge_need_target)
                        uiState.mergePlan.writableItems <= 0 -> strings.get(R.string.dedup_merge_nothing_to_write)
                        else -> strings.get(R.string.dedup_merge_confirm_write, uiState.mergePlan.writableItems)
                    }
                )
            }
            Text(
                strings.get(R.string.dedup_merge_rescan_hint),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MergeConfirmationDialog(
    uiState: DedupEngineUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val strings = rememberScreenStrings()
    val plan = uiState.mergePlan
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Merge, contentDescription = null) },
        title = { Text(strings.get(R.string.dedup_merge_confirm_title, uiState.selectedTargetOption?.label.orEmpty())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.get(R.string.dedup_merge_confirm_summary, plan.selectedSources.size, plan.writableItems))
                if (plan.conflictGroupsTotal > 0) {
                    Text(
                        strings.get(R.string.dedup_merge_confirm_conflicts, plan.conflictGroupsTotal, uiState.conflictPolicy.label(strings)),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (plan.skippedItems > 0) {
                    Text(strings.get(R.string.dedup_merge_confirm_skipped, plan.skippedItems))
                }
                Text(strings.get(R.string.dedup_merge_confirm_source_unchanged), fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(strings.get(R.string.dedup_merge_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.get(R.string.cancel)) } }
    )
}

@Composable
private fun ExecutionProgressPanel(
    completed: Int,
    total: Int,
    currentLabel: String,
    fraction: Float,
    onCancel: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.get(R.string.dedup_merge_progress, completed, total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                currentLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(strings.get(R.string.dedup_merge_stop_action)) }
        }
    }
}

@Composable
private fun ExecutionResultPanel(
    result: DedupMergeExecutionResult,
    onViewFailures: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.failedItems > 0) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.failedItems > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (result.failedItems > 0) strings.get(R.string.dedup_merge_result_partial) else strings.get(R.string.dedup_merge_result_complete),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(strings.get(R.string.dedup_merge_result_summary, result.targetLabel, result.insertedItems, result.skippedExistingItems))
                }
            }
            if (result.failures.isNotEmpty()) {
                TextButton(onClick = onViewFailures, modifier = Modifier.align(Alignment.End)) {
                    Text(strings.get(R.string.dedup_merge_view_failures, result.failures.size))
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun MessagePanel(icon: ImageVector, tint: Color, text: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PasswordPreviewRow(resolved: DedupResolvedPassword) {
    val strings = rememberScreenStrings()
    PreviewRow(
        title = resolved.entry.title.ifBlank { strings.get(R.string.dedup_merge_untitled_password) },
        subtitle = listOf(resolved.entry.username, resolved.entry.website).filter { it.isNotBlank() }.joinToString(" · "),
        sourceLabels = resolved.sourceLabels,
        copyCount = resolved.sourceEntryIds.size,
        conflictFields = resolved.conflictFields,
        existsInTarget = resolved.existsInTarget
    )
}

@Composable
private fun SecureItemPreviewRow(resolved: DedupResolvedSecureItem) {
    val strings = rememberScreenStrings()
    PreviewRow(
        title = resolved.item.title.ifBlank { resolved.item.itemType.dedupLabel(strings) },
        subtitle = resolved.item.itemType.dedupLabel(strings),
        sourceLabels = resolved.sourceLabels,
        copyCount = resolved.sourceItemIds.size,
        conflictFields = resolved.conflictFields,
        existsInTarget = resolved.existsInTarget
    )
}

@Composable
private fun PreviewRow(
    title: String,
    subtitle: String,
    sourceLabels: List<String>,
    copyCount: Int,
    conflictFields: Set<String>,
    existsInTarget: Boolean
) {
    val strings = rememberScreenStrings()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (existsInTarget) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (existsInTarget) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        if (existsInTarget) strings.get(R.string.dedup_merge_skip) else strings.get(R.string.dedup_merge_write),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Text(
                buildList {
                    add(sourceLabels.joinToString(strings.get(R.string.dedup_merge_list_separator)))
                    if (copyCount > 1) add(strings.get(R.string.dedup_merge_copies_count, copyCount))
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (conflictFields.isNotEmpty()) {
                Text(
                    strings.get(R.string.dedup_merge_conflict_fields, conflictFields.joinToString(strings.get(R.string.dedup_merge_list_separator))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun sourceColor(kind: DedupMergeSourceKind): Color = when (kind) {
    DedupMergeSourceKind.MONICA_LOCAL -> MaterialTheme.colorScheme.primary
    DedupMergeSourceKind.MDBX -> MaterialTheme.colorScheme.tertiary
    DedupMergeSourceKind.KEEPASS -> MaterialTheme.colorScheme.secondary
    DedupMergeSourceKind.BITWARDEN -> MaterialTheme.colorScheme.error
}

private fun DedupMergeSourceKind.label(): String = when (this) {
    DedupMergeSourceKind.MONICA_LOCAL -> "Monica"
    DedupMergeSourceKind.MDBX -> "MDBX"
    DedupMergeSourceKind.KEEPASS -> "KeePass"
    DedupMergeSourceKind.BITWARDEN -> "Bitwarden"
}

private fun DedupConflictPolicy.label(strings: StringResolver): String = when (this) {
    DedupConflictPolicy.MOST_COMPLETE -> strings.get(R.string.dedup_merge_policy_complete)
    DedupConflictPolicy.NEWEST -> strings.get(R.string.dedup_merge_policy_newest)
}

private fun DedupMergeSourceOption.countSummary(strings: StringResolver): String =
    itemCountParts(strings, passwordCount, secureItemCount, passkeyCount)

private fun DedupMergeTargetOption.countSummary(strings: StringResolver): String =
    itemCountParts(strings, passwordCount, secureItemCount, passkeyCount)

private fun itemCountParts(
    strings: StringResolver,
    passwordCount: Int,
    secureItemCount: Int,
    passkeyCount: Int
): String = buildList {
    add(strings.get(R.string.dedup_merge_password_count, passwordCount))
    if (secureItemCount > 0) add(strings.get(R.string.dedup_merge_secure_item_count, secureItemCount))
    if (passkeyCount > 0) add(strings.get(R.string.dedup_merge_passkey_count, passkeyCount))
}.joinToString(" · ")
