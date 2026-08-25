package takagi.ru.monica.data.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupMergeSelectionTest {
    private val localTarget = DedupMergeTargetOption(
        target = DedupMergeTarget.MonicaLocal,
        sourceKey = "monica",
        label = "Monica local",
        passwordCount = 2
    )

    @Test
    fun selectingTargetRemovesSameDatabaseFromSources() {
        val selection = DedupMergeSelection(
            sourceKeys = setOf("monica", "keepass:1", "bitwarden:1")
        ).selectTarget(localTarget)

        assertEquals(setOf("keepass:1", "bitwarden:1"), selection.sourceKeys)
        assertEquals(localTarget, selection.targetOption)
    }

    @Test
    fun selectingCurrentTargetAsSourceClearsTarget() {
        val selection = DedupMergeSelection(
            sourceKeys = setOf("keepass:1", "bitwarden:1"),
            targetOption = localTarget
        ).toggleSource("monica")

        assertEquals(setOf("monica", "keepass:1", "bitwarden:1"), selection.sourceKeys)
        assertNull(selection.targetOption)
    }

    @Test
    fun selectAllNeverIncludesCurrentTarget() {
        val selection = DedupMergeSelection(targetOption = localTarget)
            .selectAll(setOf("monica", "keepass:1", "bitwarden:1"))

        assertEquals(setOf("keepass:1", "bitwarden:1"), selection.sourceKeys)
    }

    @Test
    fun validationRequiresTwoSourcesAndOneTarget() {
        val empty = DedupMergeSelection().validate(writableItems = 1)
        assertFalse(empty.canReview)
        assertTrue(DedupMergeValidationIssue.NEED_TWO_SOURCES in empty.issues)
        assertTrue(DedupMergeValidationIssue.NEED_TARGET in empty.issues)

        val ready = DedupMergeSelection(
            sourceKeys = setOf("keepass:1", "bitwarden:1"),
            targetOption = localTarget
        ).validate(writableItems = 3)
        assertTrue(ready.canReview)
        assertTrue(ready.canExecute)
    }

    @Test
    fun validationBlocksExecutionWhenEverythingAlreadyExists() {
        val validation = DedupMergeSelection(
            sourceKeys = setOf("keepass:1", "bitwarden:1"),
            targetOption = localTarget
        ).validate(writableItems = 0)

        assertTrue(validation.canReview)
        assertFalse(validation.canExecute)
        assertEquals(setOf(DedupMergeValidationIssue.NOTHING_TO_WRITE), validation.issues)
    }
}
