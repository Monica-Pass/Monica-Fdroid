package takagi.ru.monica.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import takagi.ru.monica.bitwarden.sync.BitwardenAllVaultAutoSyncScheduler

class BitwardenAllVaultAutoSyncSchedulerTest {

    @Test
    fun allViewBatchRequestsDistinctVaultsSequentially() = runBlocking {
        val rootJob = Job()
        val scope = CoroutineScope(coroutineContext + rootJob)
        val requestedVaultIds = mutableListOf<Long>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val scheduler = BitwardenAllVaultAutoSyncScheduler(
            scope = scope,
            delayBetweenVaultsMs = 1L,
            requestSync = { vaultId ->
                requestedVaultIds += vaultId
                scope.launch {
                    when (vaultId) {
                        1L -> releaseFirst.await()
                        2L -> releaseSecond.await()
                    }
                }
            }
        )

        try {
            scheduler.begin(
                initialDelayMs = 0L,
                vaultIdsProvider = { listOf(1L, 1L, 2L) }
            )

            awaitCondition { requestedVaultIds == listOf(1L) }
            delay(25L)
            assertEquals(listOf(1L), requestedVaultIds)

            releaseFirst.complete(Unit)
            awaitCondition { requestedVaultIds == listOf(1L, 2L) }
            releaseSecond.complete(Unit)
            Unit
        } finally {
            rootJob.cancel()
        }
    }

    @Test
    fun leavingAllViewStopsPendingVaultsWithoutCancellingRunningSync() = runBlocking {
        val rootJob = Job()
        val scope = CoroutineScope(coroutineContext + rootJob)
        val requestedVaultIds = mutableListOf<Long>()
        val releaseFirst = CompletableDeferred<Unit>()
        var runningSyncJob: Job? = null
        val scheduler = BitwardenAllVaultAutoSyncScheduler(
            scope = scope,
            delayBetweenVaultsMs = 1L,
            requestSync = { vaultId ->
                requestedVaultIds += vaultId
                scope.launch {
                    if (vaultId == 1L) releaseFirst.await()
                }.also { runningSyncJob = it }
            }
        )

        try {
            val sessionId = scheduler.begin(
                initialDelayMs = 0L,
                vaultIdsProvider = { listOf(1L, 2L) }
            )
            awaitCondition { requestedVaultIds == listOf(1L) }

            scheduler.end(sessionId)
            delay(25L)

            assertEquals(listOf(1L), requestedVaultIds)
            assertFalse(runningSyncJob?.isCancelled == true)

            releaseFirst.complete(Unit)
            delay(25L)
            assertEquals(listOf(1L), requestedVaultIds)
        } finally {
            rootJob.cancel()
        }
    }

    @Test
    fun stalePageSessionCannotCancelNewAllViewBatch() = runBlocking {
        val rootJob = Job()
        val scope = CoroutineScope(coroutineContext + rootJob)
        val requestedVaultIds = mutableListOf<Long>()
        val scheduler = BitwardenAllVaultAutoSyncScheduler(
            scope = scope,
            delayBetweenVaultsMs = 0L,
            requestSync = { vaultId ->
                requestedVaultIds += vaultId
                scope.launch { }
            }
        )

        try {
            val staleSessionId = scheduler.begin(
                initialDelayMs = 1_000L,
                vaultIdsProvider = { listOf(1L) }
            )
            scheduler.begin(
                initialDelayMs = 1L,
                vaultIdsProvider = { listOf(2L) }
            )

            scheduler.end(staleSessionId)
            awaitCondition { requestedVaultIds == listOf(2L) }
        } finally {
            rootJob.cancel()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(1_000L) {
            while (!condition()) {
                yield()
            }
        }
    }
}
