package takagi.ru.monica.data.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupMergeModelsTest {
    @Test
    fun planSeparatesConflictsFromDuplicatesAndUnsupportedItems() {
        val plan = DedupMergePlan(
            uniquePasswords = 5,
            uniqueSecureItems = 2,
            duplicateGroups = 2,
            duplicateSecureItemGroups = 1,
            targetExistingDuplicates = 2,
            targetExistingSecureItems = 1,
            unsupportedSourcePasskeys = 4,
            passwordConflictGroups = 1,
            secureItemConflictGroups = 2
        )

        assertEquals(4, plan.writableItems)
        assertEquals(3, plan.duplicateGroupsTotal)
        assertEquals(3, plan.conflictGroupsTotal)
        assertEquals(7, plan.skippedItems)
    }

    @Test
    fun executionResultRetainsItemLevelFailures() {
        val result = DedupMergeExecutionResult(
            insertedPasswords = 2,
            insertedSecureItems = 1,
            skippedExistingPasswords = 3,
            failedPasswords = 1,
            failedSecureItems = 1,
            targetLabel = "Target",
            failures = listOf(
                DedupMergeFailure(DedupMergeItemKind.PASSWORD, "Mail", "write failed"),
                DedupMergeFailure(DedupMergeItemKind.SECURE_ITEM, "Card", "write failed")
            )
        )

        assertEquals(3, result.insertedItems)
        assertEquals(2, result.failedItems)
        assertTrue(result.hasPartialFailure)
        assertEquals(2, result.failures.size)
    }
}
