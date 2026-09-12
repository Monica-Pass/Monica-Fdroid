package takagi.ru.monica.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.bitwarden.sync.BitwardenPageAutoSyncScheduler
import takagi.ru.monica.bitwarden.sync.SyncTriggerReason

@OptIn(ExperimentalCoroutinesApi::class)
class BitwardenPageAutoSyncSchedulerTest {

    @Test
    fun selectedVaultWaitsForColdStartAndKeepsItsExplicitTarget() = runTest {
        val requests = mutableListOf<Pair<Long, SyncTriggerReason>>()
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, reason ->
            requests += vaultId to reason
            launch { }
        }

        scheduler.begin(8_000L, SyncTriggerReason.PAGE_ENTER) { listOf(7L) }
        advanceTimeBy(7_999L)
        runCurrent()
        assertTrue(requests.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(7L to SyncTriggerReason.PAGE_ENTER), requests)
    }

    @Test
    fun enteringNotesDuringColdStartCancelsThePreviousVaultPageRequest() = runTest {
        val requests = mutableListOf<Long>()
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, _ ->
            requests += vaultId
            launch { }
        }

        val passwordPage = scheduler.begin(8_000L, SyncTriggerReason.PAGE_ENTER) { listOf(7L) }
        // The old Compose delay has already elapsed, but startup grace is still pending.
        advanceTimeBy(2_000L)
        scheduler.end(passwordPage)
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(requests.isEmpty())
    }

    @Test
    fun switchingVaultsReplacesPendingWorkAndIgnoresStalePageDisposal() = runTest {
        val requests = mutableListOf<Long>()
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, _ ->
            requests += vaultId
            launch { }
        }

        val oldPage = scheduler.begin(8_000L, SyncTriggerReason.PAGE_ENTER) { listOf(7L) }
        advanceTimeBy(2_000L)
        scheduler.begin(6_000L, SyncTriggerReason.PAGE_ENTER) { listOf(9L) }
        scheduler.end(oldPage)
        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(listOf(9L), requests)
    }

    @Test
    fun allViewRequestsDistinctVaultsSequentiallyWithBackgroundReason() = runTest {
        val requests = mutableListOf<Pair<Long, SyncTriggerReason>>()
        val releaseFirst = CompletableDeferred<Unit>()
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, reason ->
            requests += vaultId to reason
            launch { if (vaultId == 1L) releaseFirst.await() }
        }

        scheduler.begin(0L, SyncTriggerReason.PERIODIC) { listOf(1L, 1L, 2L) }
        runCurrent()
        advanceTimeBy(10_000L)
        assertEquals(listOf(1L to SyncTriggerReason.PERIODIC), requests)

        releaseFirst.complete(Unit)
        runCurrent()
        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(1, requests.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            listOf(1L to SyncTriggerReason.PERIODIC, 2L to SyncTriggerReason.PERIODIC),
            requests
        )
    }

    @Test
    fun leavingAllViewStopsPendingVaultsWithoutCancellingRunningSync() = runTest {
        val requests = mutableListOf<Long>()
        val releaseFirst = CompletableDeferred<Unit>()
        var runningSync: Job? = null
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, _ ->
            requests += vaultId
            launch { releaseFirst.await() }.also { runningSync = it }
        }

        val page = scheduler.begin(0L, SyncTriggerReason.PERIODIC) { listOf(1L, 2L) }
        runCurrent()
        scheduler.end(page)
        runCurrent()
        assertTrue(runningSync?.isActive == true)
        assertFalse(runningSync?.isCancelled == true)

        releaseFirst.complete(Unit)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(listOf(1L), requests)
    }

    @Test
    fun leavingSelectedPageDoesNotCancelDispatchedSync() = runTest {
        val finishSync = CompletableDeferred<Unit>()
        var runningSync: Job? = null
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { _, _ ->
            launch { finishSync.await() }.also { runningSync = it }
        }

        val page = scheduler.begin(1_200L, SyncTriggerReason.PAGE_ENTER) { listOf(7L) }
        advanceTimeBy(1_200L)
        runCurrent()
        scheduler.end(page)
        runCurrent()
        assertTrue(runningSync?.isActive == true)

        finishSync.complete(Unit)
        runCurrent()
        assertTrue(runningSync?.isCompleted == true)
        assertFalse(runningSync?.isCancelled == true)
    }

    @Test
    fun changingPageWhileVaultsRestoreDiscardsTheOldProviderResult() = runTest {
        val requests = mutableListOf<Long>()
        val restoredVaults = CompletableDeferred<List<Long>>()
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, _ ->
            requests += vaultId
            launch { }
        }

        val oldPage = scheduler.begin(0L, SyncTriggerReason.PERIODIC) { restoredVaults.await() }
        runCurrent()
        scheduler.begin(1_200L, SyncTriggerReason.PAGE_ENTER) { listOf(9L) }
        scheduler.end(oldPage)
        restoredVaults.complete(listOf(1L, 2L))
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(listOf(9L), requests)
    }

    @Test
    fun leavingThePageStillCancelsAProviderThatCatchesCancellation() = runTest {
        val requests = mutableListOf<Long>()
        val restoreVaults = CompletableDeferred<List<Long>>()
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, _ ->
            requests += vaultId
            launch { }
        }

        val page = scheduler.begin(0L, SyncTriggerReason.PERIODIC) {
            try {
                restoreVaults.await()
            } catch (_: CancellationException) {
                // Repository restoration can catch an error and return its cached vaults.
                listOf(1L, 2L)
            }
        }
        runCurrent()
        scheduler.end(page)
        runCurrent()

        assertTrue(requests.isEmpty())
    }

    @Test
    fun clearingTheOwnerCancelsPendingRequests() = runTest {
        val requests = mutableListOf<Long>()
        val scheduler = BitwardenPageAutoSyncScheduler(this, 5_000L) { vaultId, _ ->
            requests += vaultId
            launch { }
        }

        scheduler.begin(8_000L, SyncTriggerReason.PAGE_ENTER) { listOf(7L) }
        advanceTimeBy(2_000L)
        scheduler.cancelPending()
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(requests.isEmpty())
    }
}
