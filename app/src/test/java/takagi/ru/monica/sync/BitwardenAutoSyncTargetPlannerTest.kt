package takagi.ru.monica.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.bitwarden.sync.BitwardenAutoSyncTargetPlanner

class BitwardenAutoSyncTargetPlannerTest {

    @Test
    fun allViewKeepsUnlockedOrderWhenActiveVaultIsUnavailable() {
        assertEquals(
            listOf(3L, 7L, 9L),
            BitwardenAutoSyncTargetPlanner.allViewTargets(
                unlockedVaultIds = listOf(3L, 7L, 7L, 9L),
                activeVaultId = 99L
            )
        )
    }

    @Test
    fun allViewDoesNotSyncAnActiveVaultThatIsLocked() {
        assertEquals(
            emptyList<Long>(),
            BitwardenAutoSyncTargetPlanner.allViewTargets(
                unlockedVaultIds = emptyList(),
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
