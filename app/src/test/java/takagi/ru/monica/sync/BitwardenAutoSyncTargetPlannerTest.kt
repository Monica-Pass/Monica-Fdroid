package takagi.ru.monica.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import takagi.ru.monica.bitwarden.sync.BitwardenAutoSyncTargetPlanner

class BitwardenAutoSyncTargetPlannerTest {

    @Test
    fun startupSelectsOnlyPreferredUnlockedVault() {
        assertEquals(
            7L,
            BitwardenAutoSyncTargetPlanner.startupTarget(
                unlockedVaultIds = listOf(3L, 7L, 9L),
                preferredVaultId = 7L,
                activeVaultId = 3L
            )
        )
    }

    @Test
    fun startupFallsBackToActiveThenFirstUnlockedVault() {
        assertEquals(
            3L,
            BitwardenAutoSyncTargetPlanner.startupTarget(
                unlockedVaultIds = listOf(3L, 7L),
                preferredVaultId = 99L,
                activeVaultId = 3L
            )
        )
        assertEquals(
            7L,
            BitwardenAutoSyncTargetPlanner.startupTarget(
                unlockedVaultIds = listOf(7L, 9L),
                preferredVaultId = 99L,
                activeVaultId = 88L
            )
        )
        assertNull(
            BitwardenAutoSyncTargetPlanner.startupTarget(
                unlockedVaultIds = emptyList(),
                preferredVaultId = 7L,
                activeVaultId = 3L
            )
        )
    }

    @Test
    fun allViewOrdersActiveVaultFirstAndRemovesDuplicates() {
        assertEquals(
            listOf(2L, 3L, 1L),
            BitwardenAutoSyncTargetPlanner.allViewTargets(
                unlockedVaultIds = listOf(3L, 2L, 3L, 1L),
                activeVaultId = 2L
            )
        )
    }
}
