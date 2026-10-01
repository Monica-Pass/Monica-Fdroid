package takagi.ru.monica.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class BackgroundSyncOwnershipTest {
    @Test fun stoppingServiceOwnerWaitsForCleanupAndReleasesSyncTarget() = runBlocking {
        val target = SyncTarget.BitwardenVault(91991L)
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val owner = launch {
            SyncTaskRunner.requestAndAwait(SyncRequest("owned", target, SyncTrigger.MANUAL, 1L),
                cancelWhenWaiterCancelled = true) {
                started.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { delay(30); cleaned.complete(Unit) } }
            }
        }
        withTimeout(2_000) { started.await() }
        withTimeout(2_000) { owner.cancelAndJoin() }
        assertTrue(cleaned.isCompleted)
        // Let coordinator publication finish before submitting a new task to the same target.
        withTimeout(2_000) { SyncTaskRunner.observe(target).first { it?.phase == SyncPhase.CANCELED } }
        val next = withTimeout(2_000) {
            SyncTaskRunner.requestAndAwait(SyncRequest("next", target, SyncTrigger.MANUAL, 2L)) { "finished" }
        }
        assertTrue(next is SyncTaskAwaitResult.Completed)
        assertEquals("finished", (next as SyncTaskAwaitResult.Completed).value)
    }

    @Test fun leavingAnOrdinaryObserverDoesNotCancelLegacySyncTasks() = runBlocking {
        val target = SyncTarget.BitwardenVault(91992L)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val observer = launch {
            SyncTaskRunner.requestAndAwait(SyncRequest("observed", target, SyncTrigger.MANUAL, 1L)) {
                started.complete(Unit); release.await(); completed.complete(Unit)
            }
        }
        withTimeout(2_000) { started.await() }
        observer.cancelAndJoin()
        assertFalse(completed.isCompleted)
        release.complete(Unit)
        withTimeout(2_000) { completed.await() }
    }
}
