package takagi.ru.monica.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.dedup.DedupMergeExecutionProgress
import takagi.ru.monica.data.dedup.DedupMergePlan
import takagi.ru.monica.data.dedup.DedupMergeTarget
import takagi.ru.monica.data.dedup.DedupMergeTargetOption

class DedupEngineViewModelTest {
    private val localTarget = DedupMergeTargetOption(
        target = DedupMergeTarget.MonicaLocal,
        sourceKey = "monica",
        label = "Monica local",
        passwordCount = 0
    )

    @Test
    fun uiStateAllowsExecutionOnlyForCompleteSelectionAndWritablePlan() {
        val ready = DedupEngineUiState(
            isLoading = false,
            selectedMergeSourceKeys = setOf("keepass:1", "bitwarden:1"),
            targetOptions = listOf(localTarget),
            selectedMergeTarget = DedupMergeTarget.MonicaLocal,
            mergePlan = DedupMergePlan(
                selectedSources = emptyList(),
                target = DedupMergeTarget.MonicaLocal,
                uniquePasswords = 2
            )
        )

        assertTrue(ready.validation.canExecute)
        assertEquals(localTarget, ready.selectedTargetOption)

        val incomplete = ready.copy(selectedMergeSourceKeys = setOf("keepass:1"))
        assertFalse(incomplete.validation.canExecute)
    }

    @Test
    fun progressFractionIsStableForEmptyAndCompletedWork() {
        assertEquals(0f, DedupMergeExecutionProgress(0, 0, "").fraction)
        assertEquals(1f, DedupMergeExecutionProgress(4, 4, "done").fraction)
    }
}
