package takagi.ru.monica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget

class KeePassMoveActionPolicyTest {
    @Test
    fun `keeps move for another group in the same KeePass database`() {
        assertFalse(
            shouldForceSupplementaryKeePassCopy(
                requestedAction = UnifiedMoveAction.MOVE,
                hasKeePassOwnedItems = true,
                target = UnifiedMoveCategoryTarget.KeePassGroupTarget(
                    databaseId = 1L,
                    groupPath = "Work"
                )
            )
        )
    }

    @Test
    fun `keeps move for a different KeePass database`() {
        assertFalse(
            shouldForceSupplementaryKeePassCopy(
                requestedAction = UnifiedMoveAction.MOVE,
                hasKeePassOwnedItems = true,
                target = UnifiedMoveCategoryTarget.KeePassDatabaseTarget(databaseId = 2L)
            )
        )
    }

    @Test
    fun `keeps move when reorganizing into Monica or MDBX`() {
        assertFalse(
            shouldForceSupplementaryKeePassCopy(
                UnifiedMoveAction.MOVE,
                true,
                UnifiedMoveCategoryTarget.MonicaCategory(categoryId = 7L)
            )
        )
        assertFalse(
            shouldForceSupplementaryKeePassCopy(
                UnifiedMoveAction.MOVE,
                true,
                UnifiedMoveCategoryTarget.MdbxDatabaseTarget(databaseId = 3L)
            )
        )
    }

    @Test
    fun `still protects unsupported destructive targets`() {
        assertTrue(
            shouldForceSupplementaryKeePassCopy(
                UnifiedMoveAction.MOVE,
                true,
                UnifiedMoveCategoryTarget.BitwardenVaultTarget(vaultId = 4L)
            )
        )
    }

    @Test
    fun `copy requests are never rewritten`() {
        assertFalse(
            shouldForceSupplementaryKeePassCopy(
                UnifiedMoveAction.COPY,
                true,
                UnifiedMoveCategoryTarget.BitwardenVaultTarget(vaultId = 4L)
            )
        )
    }
}
