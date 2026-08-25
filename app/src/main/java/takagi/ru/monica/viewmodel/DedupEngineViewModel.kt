package takagi.ru.monica.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.dedup.DedupMergeExecutionResult
import takagi.ru.monica.data.dedup.DedupMergeExecutionProgress
import takagi.ru.monica.data.dedup.DedupMergePlan
import takagi.ru.monica.data.dedup.DedupMergeSelection
import takagi.ru.monica.data.dedup.DedupMergeService
import takagi.ru.monica.data.dedup.DedupMergeSourceOption
import takagi.ru.monica.data.dedup.DedupMergeTarget
import takagi.ru.monica.data.dedup.DedupMergeTargetOption
import takagi.ru.monica.data.dedup.DedupConflictPolicy

data class DedupEngineUiState(
    val isLoading: Boolean = true,
    val isAnalyzing: Boolean = false,
    val isExecutingMerge: Boolean = false,
    val sourceOptions: List<DedupMergeSourceOption> = emptyList(),
    val selectedMergeSourceKeys: Set<String> = emptySet(),
    val targetOptions: List<DedupMergeTargetOption> = emptyList(),
    val selectedMergeTarget: DedupMergeTarget? = null,
    val conflictPolicy: DedupConflictPolicy = DedupConflictPolicy.MOST_COMPLETE,
    val mergePlan: DedupMergePlan = DedupMergePlan(),
    val executionProgress: DedupMergeExecutionProgress? = null,
    val executionResult: DedupMergeExecutionResult? = null,
    val error: String? = null,
    val message: String? = null
) {
    val selectedTargetOption: DedupMergeTargetOption?
        get() = targetOptions.firstOrNull { it.target == selectedMergeTarget }

    val selection: DedupMergeSelection
        get() = DedupMergeSelection(selectedMergeSourceKeys, selectedTargetOption)

    val validation
        get() = selection.validate(mergePlan.writableItems)
}

class DedupEngineViewModel(
    private val mergeService: DedupMergeService
) : ViewModel() {
    private val _uiState = MutableStateFlow(DedupEngineUiState())
    val uiState: StateFlow<DedupEngineUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var analyzeJob: Job? = null
    private var executionJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isExecutingMerge) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.Default) {
                    mergeService.getSourceOptions() to mergeService.getTargetOptions()
                }
            }.onSuccess { (sources, targets) ->
                val current = _uiState.value
                val validSourceKeys = sources.map { it.key }.toSet()
                val selectedKeys = current.selectedMergeSourceKeys.intersect(validSourceKeys)
                val selectedTargetSourceKey = current.selectedTargetOption?.sourceKey
                val selectedTargetOption = targets.firstOrNull { it.sourceKey == selectedTargetSourceKey }
                val selectedTarget = selectedTargetOption?.target
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sourceOptions = sources,
                        selectedMergeSourceKeys = selectedKeys - setOfNotNull(selectedTargetSourceKey),
                        targetOptions = targets,
                        selectedMergeTarget = selectedTarget,
                        error = null
                    )
                }
                rebuildPlan()
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = throwable.message ?: "去重引擎加载失败"
                    )
                }
            }
        }
    }

    fun toggleMergeSource(sourceKey: String) {
        _uiState.update { state ->
            val next = state.selection.toggleSource(sourceKey)
            state.copy(
                selectedMergeSourceKeys = next.sourceKeys,
                selectedMergeTarget = next.targetOption?.target,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun selectAllSources() {
        _uiState.update { state ->
            val next = state.selection.selectAll(state.sourceOptions.map { it.key }.toSet())
            state.copy(
                selectedMergeSourceKeys = next.sourceKeys,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun clearSources() {
        _uiState.update {
            it.copy(
                selectedMergeSourceKeys = emptySet(),
                mergePlan = DedupMergePlan(
                    target = it.selectedMergeTarget,
                    conflictPolicy = it.conflictPolicy
                ),
                executionResult = null,
                message = null,
                error = null
            )
        }
    }

    fun selectMergeTarget(target: DedupMergeTarget) {
        _uiState.update {
            val option = it.targetOptions.firstOrNull { option -> option.target == target }
                ?: return@update it
            val next = it.selection.selectTarget(option)
            it.copy(
                selectedMergeSourceKeys = next.sourceKeys,
                selectedMergeTarget = next.targetOption?.target,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun updateConflictPolicy(policy: DedupConflictPolicy) {
        if (_uiState.value.conflictPolicy == policy) return
        _uiState.update {
            it.copy(
                conflictPolicy = policy,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun executeMerge() {
        val state = _uiState.value
        val plan = state.mergePlan
        if (!state.validation.canExecute || state.isAnalyzing || state.isExecutingMerge) {
            _uiState.update {
                it.copy(message = validationMessage(state))
            }
            return
        }

        executionJob?.cancel()
        executionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExecutingMerge = true,
                    executionProgress = DedupMergeExecutionProgress(0, plan.writableItems, "准备写入"),
                    executionResult = null,
                    error = null,
                    message = null
                )
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    mergeService.executePlan(plan) { progress ->
                        _uiState.update { current -> current.copy(executionProgress = progress) }
                    }
                }
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        executionResult = result,
                        message = result.toMessage(),
                        error = null
                    )
                }
                refresh()
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        message = "已停止后续写入，已经成功完成的条目保留在目标数据库中"
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        error = throwable.message ?: "合并写入失败"
                    )
                }
            }
        }
    }

    fun cancelMerge() {
        if (!_uiState.value.isExecutingMerge) return
        executionJob?.cancel()
        _uiState.update {
            it.copy(
                message = "正在停止；当前条目完成回滚后不会继续写入"
            )
        }
    }

    fun consumeMessage() {
        if (_uiState.value.message == null) return
        _uiState.update { it.copy(message = null) }
    }

    private fun rebuildPlan() {
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            val selectedKeys = _uiState.value.selectedMergeSourceKeys
            val selectedTarget = _uiState.value.selectedMergeTarget
            val conflictPolicy = _uiState.value.conflictPolicy
            _uiState.update { it.copy(isAnalyzing = true, error = null) }
            runCatching {
                withContext(Dispatchers.Default) {
                    mergeService.buildPlan(selectedKeys, selectedTarget, conflictPolicy)
                }
            }.onSuccess { plan ->
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        mergePlan = plan,
                        error = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        error = throwable.message ?: "合并计划生成失败"
                    )
                }
            }
        }
    }
}

private fun DedupMergeExecutionResult.toMessage(): String {
    val details = buildList {
        if (insertedPasswords > 0) add("密码 $insertedPasswords")
        if (insertedSecureItems > 0) add("安全项 $insertedSecureItems")
        if (failedPasswords > 0 || failedSecureItems > 0) add("失败 ${failedPasswords + failedSecureItems}")
        if (skippedExistingItems > 0) add("跳过已有 $skippedExistingItems")
        if (skippedUnsupportedPasskeys > 0) add("通行密钥未复制 $skippedUnsupportedPasskeys")
    }.joinToString("，")
    val prefix = when {
        failedItems > 0 && insertedItems == 0 -> "未能向 $targetLabel 写入条目"
        failedItems > 0 -> "已完成部分合并，向 $targetLabel 写入 $insertedItems 条"
        else -> "已向 $targetLabel 写入 $insertedItems 条"
    }
    return prefix + details.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
}

private fun validationMessage(state: DedupEngineUiState): String {
    return when {
        state.selectedMergeSourceKeys.size < DedupMergeSelection.MINIMUM_SOURCE_DATABASES -> "请至少选择两个源数据库"
        state.selectedMergeTarget == null -> "请选择一个目标数据库"
        state.isAnalyzing -> "合并预览仍在生成"
        state.mergePlan.writableItems <= 0 -> "目标数据库已经包含全部可合并条目"
        else -> "当前合并计划无法执行"
    }
}
