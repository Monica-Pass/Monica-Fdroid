package takagi.ru.monica.data.dedup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem

class DedupMergeExecutorTest {
    @Test
    fun executionContinuesAfterIndependentFailuresAndReportsEveryItem() = runBlocking {
        val writer = FakeWriter(
            failingPasswords = setOf("Broken password"),
            failingSecureItems = setOf("Broken note")
        )
        val progress = mutableListOf<DedupMergeExecutionProgress>()

        val result = DedupMergeExecutor(writer).execute(
            passwords = listOf(password("Broken password"), password("Working password")),
            secureItems = listOf(note("Broken note")),
            skippedExistingPasswords = 2,
            skippedExistingSecureItems = 1,
            skippedUnsupportedPasskeys = 4,
            targetLabel = "Target",
            onProgress = progress::add
        )

        assertEquals(1, result.insertedPasswords)
        assertEquals(0, result.insertedSecureItems)
        assertEquals(1, result.failedPasswords)
        assertEquals(1, result.failedSecureItems)
        assertEquals(2, result.failures.size)
        assertTrue(result.hasPartialFailure)
        assertEquals(3, progress.last().completedItems)
        assertEquals(3, progress.last().totalItems)
    }

    @Test
    fun cancellationStopsTheRemainingQueue() {
        val writer = FakeWriter(cancelOnPassword = "Stop")
        var cancelled = false

        try {
            runBlocking {
                DedupMergeExecutor(writer).execute(
                    passwords = listOf(password("Stop"), password("Never written")),
                    secureItems = emptyList(),
                    skippedExistingPasswords = 0,
                    skippedExistingSecureItems = 0,
                    skippedUnsupportedPasskeys = 0,
                    targetLabel = "Target"
                )
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf("Stop"), writer.passwordAttempts)
    }

    private fun password(title: String) = DedupResolvedPassword(
        mergeKey = title,
        entry = PasswordEntry(title = title, website = "example.com", username = title, password = "secret"),
        customFields = emptyList(),
        sourceEntryIds = listOf(1L),
        sourceLabels = listOf("Source"),
        conflictFields = emptySet()
    )

    private fun note(title: String) = DedupResolvedSecureItem(
        mergeKey = title,
        item = SecureItem(itemType = ItemType.NOTE, title = title, itemData = "{}"),
        sourceItemIds = listOf(1L),
        sourceLabels = listOf("Source"),
        conflictFields = emptySet()
    )

    private class FakeWriter(
        private val failingPasswords: Set<String> = emptySet(),
        private val failingSecureItems: Set<String> = emptySet(),
        private val cancelOnPassword: String? = null
    ) : DedupMergeWriter {
        val passwordAttempts = mutableListOf<String>()

        override suspend fun writePassword(resolved: DedupResolvedPassword) {
            val title = resolved.entry.title
            passwordAttempts += title
            if (title == cancelOnPassword) throw CancellationException("cancel")
            if (title in failingPasswords) error("password write failed")
        }

        override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) {
            if (resolved.item.title in failingSecureItems) error("secure item write failed")
        }
    }
}
