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
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.dedup.DedupConflictPolicy
import takagi.ru.monica.data.dedup.DedupMergeExecutionResult
import takagi.ru.monica.data.dedup.DedupMergePlan
import takagi.ru.monica.data.dedup.DedupMergeSourceKind
import takagi.ru.monica.data.dedup.DedupMergeSourceOption
import takagi.ru.monica.data.dedup.DedupMergeTarget
import takagi.ru.monica.data.dedup.DedupMergeTargetOption
import takagi.ru.monica.data.dedup.DedupResolvedPassword
import takagi.ru.monica.data.dedup.DedupResolvedSecureItem
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
            title = { Text("停止合并？") },
            text = { Text("停止后不会继续写入。已经成功写入目标数据库的条目会保留，来源数据库不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirmation = false
                        onCancelMerge()
                    }
                ) { Text("停止") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) { Text("继续合并") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("合并数据库", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新扫描数据库")
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
                        title = "合并说明",
                        subtitle = "${uiState.mergePlan.warnings.size} 条需要留意的内容",
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
                        title = "合并明细",
                        subtitle = "${uiState.mergePlan.previewPasswords.size + uiState.mergePlan.previewSecureItems.size} 条 · ${uiState.mergePlan.conflictGroupsTotal} 个冲突组",
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
                            uiState.selectedMergeSourceKeys.size < 2 -> "选择至少两个来源数据库后生成预览"
                            uiState.selectedMergeTarget == null -> "选择目标数据库后生成预览"
                            else -> "当前选择没有可写入的新条目"
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
    val selectedSources = sources.filter { it.key in selectedSourceKeys }
    val sourceSummary = when {
        selectedSources.isEmpty() -> "未选择，至少需要两个数据库"
        selectedSources.size <= 2 -> selectedSources.joinToString("、") { it.label }
        else -> selectedSources.take(2).joinToString("、") { it.label } + " 等 ${selectedSources.size} 个"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            CompactSelectionRow(
                index = 1,
                title = "来源数据库",
                subtitle = sourceSummary,
                complete = selectedSources.size >= 2,
                enabled = enabled,
                onClick = onOpenSources
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            CompactSelectionRow(
                index = 2,
                title = "目标数据库",
                subtitle = selectedTarget?.let { "${it.label} · ${it.countSummary()}" } ?: "未选择写入目标",
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
                        Icon(Icons.Default.Check, contentDescription = "已完成", modifier = Modifier.size(19.dp))
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
        ) {
            SheetHeader("来源数据库", "已选择 ${selectedKeys.size} 个", onDismiss)
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = onSelectAllSources) { Text("全选可用项") }
                TextButton(onClick = onClearSources, enabled = selectedKeys.isNotEmpty()) { Text("清空") }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (sources.isEmpty()) {
                    item { SheetEmptyText("没有可读取的数据库") }
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
                                    "当前目标数据库，选为来源会取消目标选择"
                                } else {
                                    "${source.kind.label()} · ${source.countSummary()}"
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding()
        ) {
            SheetHeader("目标数据库", "只向目标新增内容", onDismiss)
            Text(
                "Monica 本地和 MDBX 支持写入；KeePass 与 Bitwarden 只作为来源。",
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
                                    "选择后会从来源中移除 · ${target.countSummary()}"
                                } else {
                                    target.countSummary()
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
                Text("新建 MDBX 目标数据库")
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
                title = "合并明细",
                subtitle = "${plan.previewPasswords.size + plan.previewSecureItems.size} 条内容",
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
                        label = { Text(filter.label(plan)) }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (passwords.isEmpty() && secureItems.isEmpty()) {
                    item { SheetEmptyText("当前筛选没有条目") }
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .navigationBarsPadding()
        ) {
            SheetHeader("合并说明", "${warnings.size} 条", onDismiss)
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding()
        ) {
            SheetHeader("失败记录", "${result.failures.size} 条", onDismiss)
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
        TextButton(onClick = onDismiss) { Text("完成") }
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

private fun DedupPreviewFilter.label(plan: DedupMergePlan): String = when (this) {
    DedupPreviewFilter.ALL -> "全部 ${plan.previewPasswords.size + plan.previewSecureItems.size}"
    DedupPreviewFilter.WRITE -> "写入 ${plan.writableItems}"
    DedupPreviewFilter.CONFLICT -> "冲突 ${plan.conflictGroupsTotal}"
    DedupPreviewFilter.SKIP -> "跳过 ${plan.targetExistingDuplicates + plan.targetExistingSecureItems}"
}

@Composable
private fun ConflictPolicyPanel(
    selected: DedupConflictPolicy,
    enabled: Boolean,
    onSelected: (DedupConflictPolicy) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("冲突时优先保留", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == DedupConflictPolicy.MOST_COMPLETE,
                onClick = { onSelected(DedupConflictPolicy.MOST_COMPLETE) },
                enabled = enabled,
                label = { Text("内容更完整") },
                leadingIcon = if (selected == DedupConflictPolicy.MOST_COMPLETE) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null
            )
            FilterChip(
                selected = selected == DedupConflictPolicy.NEWEST,
                onClick = { onSelected(DedupConflictPolicy.NEWEST) },
                enabled = enabled,
                label = { Text("最近更新") },
                leadingIcon = if (selected == DedupConflictPolicy.NEWEST) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null
            )
        }
        Text(
            "仅决定同一条目字段冲突时的基础版本；空字段仍会从其他副本补全。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MergeSummaryPanel(uiState: DedupEngineUiState) {
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
                    Text("合并预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            uiState.isExecutingMerge -> "正在写入目标数据库"
                            uiState.isAnalyzing -> "正在重新分析"
                            else -> uiState.selectedTargetOption?.label ?: "尚未选择目标数据库"
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
                SummaryPill("来源", plan.totalSourceItems)
                SummaryPill("将写入", plan.writableItems)
                SummaryPill("目标已有", plan.targetExistingDuplicates + plan.targetExistingSecureItems)
                SummaryPill("重复组", plan.duplicateGroupsTotal)
                SummaryPill("冲突组", plan.conflictGroupsTotal)
                if (plan.unsupportedSourcePasskeys > 0) SummaryPill("不支持", plan.unsupportedSourcePasskeys)
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, value: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Text(
            "$label $value",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun MergeBottomBar(uiState: DedupEngineUiState, onReviewAndMerge: () -> Unit) {
    val validation = uiState.validation
    Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                        uiState.isExecutingMerge -> "正在合并"
                        uiState.selectedMergeSourceKeys.size < 2 -> "请选择至少两个来源数据库"
                        uiState.selectedMergeTarget == null -> "请选择目标数据库"
                        uiState.mergePlan.writableItems <= 0 -> "没有需要写入的条目"
                        else -> "确认并写入 ${uiState.mergePlan.writableItems} 条"
                    }
                )
            }
            Text(
                "执行前会再次扫描目标数据库，避免写入刚刚新增的重复项。",
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
    val plan = uiState.mergePlan
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Merge, contentDescription = null) },
        title = { Text("确认合并到 ${uiState.selectedTargetOption?.label.orEmpty()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${plan.selectedSources.size} 个来源数据库将合并出 ${plan.writableItems} 条新内容。")
                if (plan.conflictGroupsTotal > 0) {
                    Text(
                        "${plan.conflictGroupsTotal} 个冲突组将优先保留${uiState.conflictPolicy.label()}的版本。",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (plan.skippedItems > 0) {
                    Text("${plan.skippedItems} 条目标已有或不支持的内容不会写入。")
                }
                Text("来源数据库不会被修改。此操作只向目标数据库新增条目。", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("开始合并") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
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
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("正在合并 $completed / $total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                currentLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("停止") }
        }
    }
}

@Composable
private fun ExecutionResultPanel(
    result: DedupMergeExecutionResult,
    onViewFailures: () -> Unit
) {
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
                        if (result.failedItems > 0) "合并完成，但有失败项" else "合并完成",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("${result.targetLabel} · 写入 ${result.insertedItems} 条 · 跳过 ${result.skippedExistingItems} 条")
                }
            }
            if (result.failures.isNotEmpty()) {
                TextButton(onClick = onViewFailures, modifier = Modifier.align(Alignment.End)) {
                    Text("查看 ${result.failures.size} 条失败记录")
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
    PreviewRow(
        title = resolved.entry.title.ifBlank { "未命名密码" },
        subtitle = listOf(resolved.entry.username, resolved.entry.website).filter { it.isNotBlank() }.joinToString(" · "),
        sourceLabels = resolved.sourceLabels,
        copyCount = resolved.sourceEntryIds.size,
        conflictFields = resolved.conflictFields,
        existsInTarget = resolved.existsInTarget
    )
}

@Composable
private fun SecureItemPreviewRow(resolved: DedupResolvedSecureItem) {
    PreviewRow(
        title = resolved.item.title.ifBlank { resolved.item.itemType.label() },
        subtitle = resolved.item.itemType.label(),
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
                        if (existsInTarget) "跳过" else "写入",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Text(
                buildList {
                    add(sourceLabels.joinToString("、"))
                    if (copyCount > 1) add("$copyCount 个副本")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (conflictFields.isNotEmpty()) {
                Text(
                    "冲突字段：${conflictFields.joinToString("、")}",
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

private fun DedupConflictPolicy.label(): String = when (this) {
    DedupConflictPolicy.MOST_COMPLETE -> "内容更完整"
    DedupConflictPolicy.NEWEST -> "最近更新"
}

private fun DedupMergeSourceOption.countSummary(): String = itemCountParts(passwordCount, secureItemCount, passkeyCount)

private fun DedupMergeTargetOption.countSummary(): String = itemCountParts(passwordCount, secureItemCount, passkeyCount)

private fun itemCountParts(passwordCount: Int, secureItemCount: Int, passkeyCount: Int): String = buildList {
    add("$passwordCount 条密码")
    if (secureItemCount > 0) add("$secureItemCount 个安全项")
    if (passkeyCount > 0) add("$passkeyCount 个通行密钥")
}.joinToString(" · ")

private fun ItemType.label(): String = when (this) {
    ItemType.PASSWORD -> "密码"
    ItemType.TOTP -> "验证器"
    ItemType.BANK_CARD -> "银行卡"
    ItemType.DOCUMENT -> "证件"
    ItemType.BILLING_ADDRESS -> "账单地址"
    ItemType.PAYMENT_ACCOUNT -> "支付方式"
    ItemType.NOTE -> "笔记"
}
