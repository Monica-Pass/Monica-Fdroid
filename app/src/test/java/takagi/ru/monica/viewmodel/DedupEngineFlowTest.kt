package takagi.ru.monica.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.dedup.*
import takagi.ru.monica.utils.StringResolver

@OptIn(ExperimentalCoroutinesApi::class)
class DedupEngineFlowTest {
    @Test fun clearingSourcesDuringScanCannotRestoreAStalePreview() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val service = FakeService()
        val model = DedupEngineViewModel(service, StringResolver { _, _ -> "message" }, dispatcher)
        try {
            advanceUntilIdle()
            service.gate = CompletableDeferred()
            model.toggleMergeSource("keepass:1")
            model.selectMergeTarget(DedupMergeTarget.MonicaLocal)
            runCurrent()
            assertTrue(model.uiState.value.isAnalyzing)
            model.clearSources()
            service.gate!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(model.uiState.value.selectedMergeSourceKeys.isEmpty())
            assertFalse(model.uiState.value.isAnalyzing)
            assertFalse(model.uiState.value.canExecuteMerge)
            assertEquals(0, model.uiState.value.mergePlan.writableItems)
            assertNull(model.uiState.value.error)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun failedRescanCannotExecuteThePreviousPlan() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val service = FakeService()
        val model = DedupEngineViewModel(service, StringResolver { _, _ -> "message" }, dispatcher)
        try {
            advanceUntilIdle()
            model.toggleMergeSource("keepass:1")
            model.selectMergeTarget(DedupMergeTarget.MonicaLocal)
            advanceUntilIdle()
            assertTrue(model.uiState.value.canExecuteMerge)
            service.fail = true
            model.updateConflictPolicy(DedupConflictPolicy.NEWEST)
            assertFalse(model.uiState.value.canExecuteMerge)
            advanceUntilIdle()
            assertNotNull(model.uiState.value.error)
            model.executeMerge()
            advanceUntilIdle()
            assertEquals(0, service.executions)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun selectingSearchResultsKeepsSelectionsOutsideTheSearch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val model = DedupEngineViewModel(FakeService(), StringResolver { _, _ -> "message" }, dispatcher)
        try {
            advanceUntilIdle()
            model.selectMergeTarget(DedupMergeTarget.MonicaLocal)
            model.toggleMergeSource("keepass:1")
            model.selectAllSources(setOf("bitwarden:1", "monica", "missing"))
            advanceUntilIdle()
            assertEquals(setOf("keepass:1", "bitwarden:1"), model.uiState.value.selectedMergeSourceKeys)
            assertEquals(DedupMergeTarget.MonicaLocal, model.uiState.value.selectedMergeTarget)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private class FakeService : DedupMergeOperations {
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var executions = 0
        private val sources = listOf(
            DedupMergeSourceOption("keepass:1", DedupMergeSourceKind.KEEPASS, "A", 1),
            DedupMergeSourceOption("bitwarden:1", DedupMergeSourceKind.BITWARDEN, "B", 1)
        )
        override suspend fun getSourceOptions() = sources
        override suspend fun getTargetOptions() = listOf(DedupMergeTargetOption(DedupMergeTarget.MonicaLocal, "monica", "Local", 0))
        override suspend fun buildPlan(selectedSourceKeys: Set<String>, target: DedupMergeTarget?, conflictPolicy: DedupConflictPolicy): DedupMergePlan {
            gate?.await()
            check(!fail) { "Cannot read database" }
            return DedupMergePlan(selectedSources = sources.filter { it.key in selectedSourceKeys }, target = target,
                conflictPolicy = conflictPolicy, uniquePasswords = 1)
        }
        override suspend fun executePlan(plan: DedupMergePlan, onProgress: (DedupMergeExecutionProgress) -> Unit): DedupMergeExecutionResult {
            executions++
            return DedupMergeExecutionResult(1, skippedExistingPasswords = 0, failedPasswords = 0, targetLabel = "Local")
        }
    }
}
