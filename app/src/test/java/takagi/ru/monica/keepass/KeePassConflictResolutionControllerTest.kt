package takagi.ru.monica.keepass

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeePassConflictResolutionControllerTest {
    @Test fun failedComparisonCanBeRefreshedWithoutLeavingTheReview() = runTest {
        var attempts = 0
        val controller = KeePassConflictResolutionController(
            scope = this,
            inspect = { if (++attempts == 1) Result.failure(IllegalStateException("Offline")) else Result.success(preview()) },
            resolve = { _, _, _, _, _ -> error("Unexpected write") },
            formatError = { it.message.orEmpty() }
        )
        controller.open(1, "Work")
        runCurrent()
        assertEquals("Offline", controller.state.value!!.error)
        assertFalse(controller.state.value!!.loading)
        controller.refresh()
        assertTrue(controller.state.value!!.loading)
        runCurrent()
        assertNotNull(controller.state.value!!.preview)
        assertNull(controller.state.value!!.error)
    }

    @Test fun staleComparisonCannotReplaceAnotherDatabaseOrReopenADismissedSheet() = runTest {
        val slow = CompletableDeferred<Result<KeePassRemoteConflictPreview>>()
        val controller = KeePassConflictResolutionController(
            scope = this,
            inspect = { id -> if (id == 1L) withContext(NonCancellable) { slow.await() } else Result.success(preview("new")) },
            resolve = { _, _, _, _, _ -> error("Unexpected write") },
            formatError = { it.message.orEmpty() }
        )
        controller.open(1, "Old")
        runCurrent()
        controller.open(2, "New")
        runCurrent()
        assertEquals(2L, controller.state.value!!.databaseId)
        controller.dismiss()
        slow.complete(Result.success(preview("stale")))
        runCurrent()
        assertNull(controller.state.value)
    }

    @Test fun everyDisputedFieldMustBeChosenAndTheReviewedRevisionsReachTheWriter() = runTest {
        val writes = mutableListOf<List<Any>>()
        var notified = 0L
        val controller = KeePassConflictResolutionController(
            scope = this,
            inspect = { Result.success(preview(details = listOf(detail("password"), detail("location")))) },
            resolve = { id, decision, local, remote, choices ->
                writes += listOf(id, decision, local, remote, choices)
                Result.success(resolution())
            },
            formatError = { it.message.orEmpty() },
            onResolved = { id, _ -> notified = id }
        )
        controller.open(7, "Work")
        runCurrent()
        controller.submit(KeePassConflictDecision.MERGE, mapOf("password" to KeePassConflictResolutionSide.LOCAL))
        runCurrent()
        assertTrue(writes.isEmpty())
        val choices = mapOf("password" to KeePassConflictResolutionSide.LOCAL, "location" to KeePassConflictResolutionSide.REMOTE)
        controller.submit(KeePassConflictDecision.MERGE, choices)
        runCurrent()
        assertEquals(listOf(7L, KeePassConflictDecision.MERGE, "local", "remote", choices), writes.single())
        assertNull(controller.state.value)
        assertEquals(7L, notified)
        assertEquals(1L, controller.resolutionVersion.value)
    }

    @Test fun independentChangesCanBeSubmittedWithNoManualChoices() = runTest {
        var calls = 0
        val controller = KeePassConflictResolutionController(
            scope = this,
            inspect = { Result.success(preview()) },
            resolve = { _, decision, _, _, choices ->
                assertEquals(KeePassConflictDecision.MERGE, decision)
                assertTrue(choices.isEmpty())
                calls++
                Result.success(resolution())
            },
            formatError = { it.message.orEmpty() }
        )
        controller.open(1, "Work")
        runCurrent()
        controller.submit(KeePassConflictDecision.MERGE, emptyMap())
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun writingBlocksDuplicateSubmissionRefreshAndDismissUntilItCompletes() = runTest {
        val write = CompletableDeferred<Result<KeePassRemoteConflictResolution>>()
        var calls = 0
        val controller = KeePassConflictResolutionController(
            scope = this,
            inspect = { Result.success(preview()) },
            resolve = { _, _, _, _, _ -> calls++; write.await() },
            formatError = { it.message.orEmpty() }
        )
        controller.open(1, "Work")
        runCurrent()
        controller.submit(KeePassConflictDecision.MERGE, emptyMap())
        runCurrent()
        controller.submit(KeePassConflictDecision.USE_REMOTE, emptyMap())
        controller.refresh()
        controller.dismiss()
        controller.open(2, "Another database")
        assertTrue(controller.state.value!!.resolving)
        assertEquals(1L, controller.state.value!!.databaseId)
        assertEquals(1, calls)
        write.complete(Result.success(resolution()))
        runCurrent()
        assertNull(controller.state.value)
    }

    @Test fun anUnconfirmedWriteInvalidatesThePreviewBeforeRetry() = runTest {
        var comparisons = 0
        var writes = 0
        val controller = KeePassConflictResolutionController(
            scope = this,
            inspect = { Result.success(preview("local-${++comparisons}")) },
            resolve = { _, _, local, _, _ ->
                writes++
                if (writes == 1) Result.failure(IllegalStateException("Verification failed"))
                else {
                    assertEquals("local-2", local)
                    Result.success(resolution())
                }
            },
            formatError = { it.message.orEmpty() }
        )
        controller.open(1, "Work")
        runCurrent()
        controller.submit(KeePassConflictDecision.MERGE, emptyMap())
        runCurrent()
        assertEquals("Verification failed", controller.state.value!!.error)
        assertNull(controller.state.value!!.preview)
        assertEquals(0L, controller.resolutionVersion.value)
        controller.submit(KeePassConflictDecision.MERGE, emptyMap())
        runCurrent()
        assertEquals(1, writes)
        controller.refresh()
        runCurrent()
        controller.submit(KeePassConflictDecision.MERGE, emptyMap())
        runCurrent()
        assertEquals(2, writes)
        assertNull(controller.state.value)
    }

    @Test fun cancelledBackendWriteReleasesTheBusyStateAndRequiresFreshComparison() = runTest {
        val controller = KeePassConflictResolutionController(
            scope = this,
            inspect = { Result.success(preview()) },
            resolve = { _, _, _, _, _ -> Result.failure(CancellationException("Write cancelled")) },
            formatError = { it.message.orEmpty() }
        )
        controller.open(1, "Work")
        runCurrent()
        controller.submit(KeePassConflictDecision.MERGE, emptyMap())
        runCurrent()
        assertFalse(controller.state.value!!.resolving)
        assertNull(controller.state.value!!.preview)
        controller.dismiss()
        assertNull(controller.state.value)
    }

    private fun preview(local: String = "local", details: List<KeePassConflictDetail> = emptyList()) = KeePassRemoteConflictPreview(
        snapshot = KeePassConflictSnapshot(
            items = listOf(KeePassConflictItem("entry", KeePassConflictObjectType.ENTRY, "Account",
                KeePassConflictChangeType.MODIFIED, KeePassConflictChangeType.MODIFIED,
                details.isNotEmpty(), null, null, details)),
            localChangeCount = 1, remoteChangeCount = 1,
            ambiguousCount = if (details.isEmpty()) 0 else 1, mergeRecommended = details.isEmpty()
        ),
        localRevision = KeePassSourceRevision(local, 10),
        remoteRevision = KeePassSourceRevision("remote", 20),
        baseRevision = KeePassSourceRevision("base", 5),
        remoteVersionToken = "etag", remoteSizeBytes = 20
    )

    private fun detail(id: String) = KeePassConflictDetail(id, KeePassConflictDetailKind.FIELD, id, "local", "remote")
    private fun resolution() = KeePassRemoteConflictResolution(KeePassConflictDecision.MERGE, 0, KeePassSourceRevision("merged", 25), false, 2)
}
