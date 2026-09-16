package takagi.ru.monica.sync

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.BitwardenCoordinatedSyncResult
import takagi.ru.monica.bitwarden.sync.SyncBlockReason
import takagi.ru.monica.bitwarden.sync.VaultSyncStatus
import takagi.ru.monica.bitwarden.sync.awaitCoordinatedBitwardenSync
import takagi.ru.monica.bitwarden.sync.mergeBitwardenSyncStatuses
import takagi.ru.monica.localization.xmlTestStrings

class BitwardenSyncStatusRegressionTest {
    private val strings by lazy { xmlTestStrings("en") }

    @Test
    fun returnedConnectionResetFailsCoordinatorAndPreservesRepositoryError() = runBlocking {
        val error = BitwardenRepository.SyncResult.Error("Sync failed: Connection reset")
        val (result, status) = execute(92_001L, error)

        assertSame(error, (result as BitwardenCoordinatedSyncResult.Completed).result)
        assertEquals(SyncPhase.FAILED, status.phase)
        assertEquals(SyncErrorKind.NETWORK_UNAVAILABLE, status.lastError?.kind)
        assertEquals(true, status.lastError?.retryable)
        assertNull(status.lastSuccessAtMillis)
        assertEquals(
            error.message,
            mergeBitwardenSyncStatuses(emptyMap(), listOf(status)).getValue(92_001L).lastError
        )
    }

    @Test
    fun successfulRepositoryResultPublishesSuccess() = runBlocking {
        val success = BitwardenRepository.SyncResult.Success(0, 0, 0, 0, 0, 8, 0, 0, 0)
        val (result, status) = execute(92_002L, success)

        assertSame(success, (result as BitwardenCoordinatedSyncResult.Completed).result)
        assertEquals(SyncPhase.SUCCESS, status.phase)
        assertEquals(status.lastFinishedAtMillis, status.lastSuccessAtMillis)
        assertNull(status.lastError)
    }

    @Test
    fun lockedVaultResultIsBlockedWithoutLosingTypedResult() = runBlocking {
        val error = BitwardenRepository.SyncResult.Error(strings.get(R.string.legacy_ui_vault_locked))
        val (result, status) = execute(92_003L, error)

        assertSame(error, (result as BitwardenCoordinatedSyncResult.Completed).result)
        assertEquals(SyncPhase.BLOCKED, status.phase)
        assertEquals(SyncErrorKind.TARGET_LOCKED, status.lastError?.kind)
        assertNull(status.lastSuccessAtMillis)
    }

    @Test
    fun emptyVaultProtectionNeverPublishesSuccess() = runBlocking {
        val blocked = BitwardenRepository.SyncResult.EmptyVaultBlocked(92_004L, 8, 0, "Empty vault protection")
        val (result, status) = execute(92_004L, blocked)

        assertSame(blocked, (result as BitwardenCoordinatedSyncResult.Completed).result)
        assertEquals(SyncPhase.FAILED, status.phase)
        assertEquals(SyncErrorKind.VALIDATION_FAILED, status.lastError?.kind)
        assertNull(status.lastSuccessAtMillis)
    }

    @Test
    fun laterCoordinatorSuccessClearsOldFailureAndBlock() {
        val existing = VaultSyncStatus(
            lastError = "Connection reset",
            blockedReason = SyncBlockReason.NETWORK_UNAVAILABLE,
            lastOutcomeAt = 100L
        )
        val merged = merge(existing, terminalStatus(SyncPhase.SUCCESS, 200L))

        assertNull(merged.lastError)
        assertNull(merged.blockedReason)
        assertEquals(200L, merged.lastSuccessAt)
        assertEquals(200L, merged.lastOutcomeAt)
        assertFalse(merged.isRunning)
    }

    @Test
    fun cachedCoordinatorSuccessCannotHideNewerPreflightBlock() {
        val existing = VaultSyncStatus(
            lastError = "Wi-Fi required",
            blockedReason = SyncBlockReason.WIFI_REQUIRED,
            lastOutcomeAt = 300L
        )
        val merged = merge(existing, terminalStatus(SyncPhase.SUCCESS, 200L))

        assertEquals("Wi-Fi required", merged.lastError)
        assertEquals(SyncBlockReason.WIFI_REQUIRED, merged.blockedReason)
        assertEquals(300L, merged.lastOutcomeAt)
        assertEquals(200L, merged.lastSuccessAt)
    }

    @Test
    fun cachedCoordinatorFailureCannotOverwriteNewerSuccess() {
        val existing = VaultSyncStatus(lastSuccessAt = 300L, lastOutcomeAt = 300L)
        val merged = merge(existing, terminalStatus(SyncPhase.FAILED, 200L))

        assertNull(merged.lastError)
        assertNull(merged.blockedReason)
        assertEquals(300L, merged.lastSuccessAt)
        assertEquals(300L, merged.lastOutcomeAt)
    }

    @Test
    fun laterCoordinatorFailureReplacesOldBlock() {
        val existing = VaultSyncStatus(
            lastError = "Vault locked",
            blockedReason = SyncBlockReason.VAULT_LOCKED,
            lastOutcomeAt = 100L
        )
        val merged = merge(existing, terminalStatus(SyncPhase.FAILED, 200L))

        assertEquals("Connection reset", merged.lastError)
        assertNull(merged.blockedReason)
        assertEquals(200L, merged.lastOutcomeAt)
    }

    private suspend fun execute(
        vaultId: Long,
        outcome: BitwardenRepository.SyncResult
    ): Pair<BitwardenCoordinatedSyncResult, SyncTaskStatus> {
        val request = SyncRequest(
            requestId = "bitwarden-status-$vaultId",
            target = SyncTarget.BitwardenVault(vaultId),
            trigger = SyncTrigger.MANUAL,
            createdAtMillis = System.currentTimeMillis()
        )
        val result = awaitCoordinatedBitwardenSync(request, strings) { outcome }
        val status = withTimeout(2_000L) {
            SyncTaskRunner.observe(request.target).filterNotNull().first { it.lastFinishedAtMillis != null }
        }
        assertTrue(result is BitwardenCoordinatedSyncResult.Completed)
        return result to status
    }

    private fun merge(existing: VaultSyncStatus, incoming: SyncTaskStatus): VaultSyncStatus =
        mergeBitwardenSyncStatuses(mapOf(42L to existing), listOf(incoming)).getValue(42L)

    private fun terminalStatus(phase: SyncPhase, finishedAt: Long): SyncTaskStatus = SyncTaskStatus(
        key = SyncKey("bitwarden:42"),
        target = SyncTarget.BitwardenVault(42L),
        phase = phase,
        lastTrigger = SyncTrigger.MANUAL,
        lastFinishedAtMillis = finishedAt,
        lastSuccessAtMillis = finishedAt.takeIf { phase == SyncPhase.SUCCESS },
        lastError = if (phase == SyncPhase.FAILED) {
            SyncError(SyncErrorKind.NETWORK_UNAVAILABLE, "Connection reset", retryable = true)
        } else null
    )
}
