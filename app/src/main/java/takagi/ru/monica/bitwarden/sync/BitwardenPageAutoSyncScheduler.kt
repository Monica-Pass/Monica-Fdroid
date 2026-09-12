package takagi.ru.monica.bitwarden.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Owns pending page requests; dispatched sync jobs keep their independent lifetime. */
internal class BitwardenPageAutoSyncScheduler(
    private val scope: CoroutineScope,
    private val delayBetweenVaultsMs: Long,
    private val requestSync: (Long, SyncTriggerReason) -> Job
) {
    companion object {
        const val PAGE_ENTER_AUTO_SYNC_DELAY_MS = 1_200L
    }

    private val stateLock = Any()
    private var nextSessionId = 0L
    private var activeSessionId: Long? = null
    private var batchJob: Job? = null

    fun begin(
        initialDelayMs: Long,
        reason: SyncTriggerReason,
        vaultIdsProvider: suspend () -> List<Long>
    ): Long {
        val sessionId: Long
        val sessionJob: Job
        synchronized(stateLock) {
            batchJob?.cancel()
            sessionId = ++nextSessionId
            activeSessionId = sessionId
            sessionJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (initialDelayMs > 0L) delay(initialDelayMs)
                    val vaultIds = vaultIdsProvider().distinct()
                    vaultIds.forEachIndexed { index, vaultId ->
                        if (index > 0 && delayBetweenVaultsMs > 0L) {
                            delay(delayBetweenVaultsMs)
                        }
                        // A repository may catch cancellation and return cached vaults.
                        currentCoroutineContext().ensureActive()
                        requestSync(vaultId, reason).join()
                    }
                } finally {
                    synchronized(stateLock) {
                        if (activeSessionId == sessionId) {
                            activeSessionId = null
                            batchJob = null
                        }
                    }
                }
            }
            batchJob = sessionJob
        }
        sessionJob.start()
        return sessionId
    }

    fun end(sessionId: Long) {
        val jobToCancel = synchronized(stateLock) {
            if (activeSessionId != sessionId) return
            activeSessionId = null
            batchJob.also { batchJob = null }
        }
        jobToCancel?.cancel()
    }

    fun cancelPending() {
        val jobToCancel = synchronized(stateLock) {
            activeSessionId = null
            batchJob.also { batchJob = null }
        }
        jobToCancel?.cancel()
    }
}
