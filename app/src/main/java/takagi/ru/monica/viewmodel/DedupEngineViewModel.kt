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
import takagi.ru.monica.R
import takagi.ru.monica.data.dedup.DedupMergeExecutionResult
import takagi.ru.monica.data.dedup.DedupMergeExecutionProgress
import takagi.ru.monica.data.dedup.DedupMergePlan
import takagi.ru.monica.data.dedup.DedupMergeSelection
import takagi.ru.monica.data.dedup.DedupMergeService
import takagi.ru.monica.data.dedup.DedupMergeSourceOption
import takagi.ru.monica.data.dedup.DedupMergeTarget
import takagi.ru.monica.data.dedup.DedupMergeTargetOption
import takagi.ru.monica.data.dedup.DedupConflictPolicy
import takagi.ru.monica.utils.StringResolver

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

class DedupEngineViewModel internal constructor(
    private val mergeService: DedupMergeService,
    private val strings: StringResolver
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
                        error = throwable.message ?: strings.get(R.string.dedup_merge_load_failed)
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
                it.copy(message = validationMessage(state, strings))
            }
            return
        }

        executionJob?.cancel()
        executionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExecutingMerge = true,
                    executionProgress = DedupMergeExecutionProgress(0, plan.writableItems, strings.get(R.string.dedup_merge_preparing)),
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
                        message = result.toMessage(strings),
                        error = null
                    )
                }
                refresh()
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        message = strings.get(R.string.dedup_merge_cancelled)
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        error = throwable.message ?: strings.get(R.string.dedup_merge_write_failed)
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
                message = strings.get(R.string.dedup_merge_cancelling)
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
                        error = throwable.message ?: strings.get(R.string.dedup_merge_plan_failed)
                    )
                }
            }
        }
    }
}

private fun DedupMergeExecutionResult.toMessage(strings: StringResolver): String {
    val details = buildList {
        if (insertedPasswords > 0) add(strings.get(R.string.dedup_merge_password_count, insertedPasswords))
        if (insertedSecureItems > 0) add(strings.get(R.string.dedup_merge_secure_item_count, insertedSecureItems))
        if (failedItems > 0) add(strings.get(R.string.dedup_merge_failed_count, failedItems))
        if (skippedExistingItems > 0) add(strings.get(R.string.dedup_merge_skipped_existing_count, skippedExistingItems))
        if (skippedUnsupportedPasskeys > 0) add(strings.get(R.string.dedup_merge_skipped_passkey_count, skippedUnsupportedPasskeys))
    }.joinToString(strings.get(R.string.dedup_merge_list_separator))
    val summary = when {
        failedItems > 0 && insertedItems == 0 -> strings.get(R.string.dedup_merge_message_failed, targetLabel)
        failedItems > 0 -> strings.get(R.string.dedup_merge_message_partial, targetLabel, insertedItems)
        else -> strings.get(R.string.dedup_merge_message_success, targetLabel, insertedItems)
    }
    return if (details.isBlank()) summary else strings.get(R.string.dedup_merge_message_details, summary, details)
}

private fun validationMessage(state: DedupEngineUiState, strings: StringResolver): String = strings.get(
    when {
        state.selectedMergeSourceKeys.size < DedupMergeSelection.MINIMUM_SOURCE_DATABASES -> R.string.dedup_merge_need_sources
        state.selectedMergeTarget == null -> R.string.dedup_merge_need_target
        state.isAnalyzing -> R.string.dedup_merge_preview_pending
        state.mergePlan.writableItems <= 0 -> R.string.dedup_merge_target_contains_all
        else -> R.string.dedup_merge_plan_invalid
    }
)
