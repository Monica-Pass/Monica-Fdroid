package takagi.ru.monica.steam

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.steam.data.requireSteamBitwardenSyncSuccess

class SteamBitwardenSyncPolicyTest {

    @Test
    fun partialUploadFailureCannotBeReportedAsSteamTransferSuccess() {
        val error = expectFailure {
            requireSteamBitwardenSyncSuccess(
                result = success(uploadFailedCount = 1),
                operation = "upsert"
            )
        }

        assertTrue(error.message.orEmpty().contains("upload", ignoreCase = true))
    }

    @Test
    fun emptyVaultProtectionCannotBeReportedAsSteamTransferSuccess() {
        val error = expectFailure {
            requireSteamBitwardenSyncSuccess(
                result = BitwardenRepository.SyncResult.EmptyVaultBlocked(
                    vaultId = 1,
                    localCount = 1,
                    serverCount = 0,
                    reason = "blocked"
                ),
                operation = "upsert"
            )
        }

        assertTrue(error.message.orEmpty().contains("blocked", ignoreCase = true))
    }

    @Test
    fun completeSyncIsAccepted() {
        requireSteamBitwardenSyncSuccess(
            result = success(uploadFailedCount = 0),
            operation = "upsert"
        )
    }

    private fun success(uploadFailedCount: Int) = BitwardenRepository.SyncResult.Success(
        appliedChangeCount = 1,
        remoteAddedCount = 0,
        remoteUpdatedCount = 0,
        uploadedCount = 1,
        deletedCount = 0,
        availableOfflineCount = 1,
        conflictCount = 0,
        uploadFailedCount = uploadFailedCount,
        skippedDueToLocalDirtyCount = 0
    )

    private fun expectFailure(block: () -> Unit): Throwable {
        return try {
            block()
            fail("Expected operation to fail")
            error("unreachable")
        } catch (error: Throwable) {
            error
        }
    }
}
