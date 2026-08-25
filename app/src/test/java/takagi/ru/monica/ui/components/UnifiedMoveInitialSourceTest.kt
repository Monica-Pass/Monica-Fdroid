package takagi.ru.monica.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedMoveInitialSourceTest {

    @Test
    fun `local filters use Monica as the initial database`() {
        val filters = listOf(
            UnifiedCategoryFilterSelection.Local,
            UnifiedCategoryFilterSelection.LocalStarred,
            UnifiedCategoryFilterSelection.LocalUncategorized,
            UnifiedCategoryFilterSelection.Custom(categoryId = 7L),
        )

        filters.forEach { filter ->
            assertEquals(
                UnifiedMoveInitialSource.MonicaLocal,
                filter.toUnifiedMoveInitialSource(),
            )
        }
    }

    @Test
    fun `global filters fall back to Monica`() {
        val filters = listOf(
            UnifiedCategoryFilterSelection.All,
            UnifiedCategoryFilterSelection.Starred,
            UnifiedCategoryFilterSelection.Uncategorized,
        )

        filters.forEach { filter ->
            assertEquals(
                UnifiedMoveInitialSource.MonicaLocal,
                filter.toUnifiedMoveInitialSource(),
            )
        }
    }

    @Test
    fun `Bitwarden filters use their owning vault`() {
        val filters = listOf(
            UnifiedCategoryFilterSelection.BitwardenVaultFilter(vaultId = 11L),
            UnifiedCategoryFilterSelection.BitwardenFolderFilter(vaultId = 11L, folderId = "folder"),
            UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(vaultId = 11L),
            UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(vaultId = 11L),
        )

        filters.forEach { filter ->
            assertEquals(
                UnifiedMoveInitialSource.BitwardenVault(vaultId = 11L),
                filter.toUnifiedMoveInitialSource(),
            )
        }
    }

    @Test
    fun `KeePass filters use their owning database`() {
        val filters = listOf(
            UnifiedCategoryFilterSelection.KeePassDatabaseFilter(databaseId = 21L),
            UnifiedCategoryFilterSelection.KeePassGroupFilter(
                databaseId = 21L,
                groupPath = "Root/Work",
            ),
            UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(databaseId = 21L),
            UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(databaseId = 21L),
        )

        filters.forEach { filter ->
            assertEquals(
                UnifiedMoveInitialSource.KeePassDatabase(databaseId = 21L),
                filter.toUnifiedMoveInitialSource(),
            )
        }
    }

    @Test
    fun `MDBX filters use their owning database`() {
        val filters = listOf(
            UnifiedCategoryFilterSelection.MdbxDatabaseFilter(databaseId = 31L),
            UnifiedCategoryFilterSelection.MdbxFolderFilter(databaseId = 31L, folderId = "folder"),
        )

        filters.forEach { filter ->
            assertEquals(
                UnifiedMoveInitialSource.MdbxDatabase(databaseId = 31L),
                filter.toUnifiedMoveInitialSource(),
            )
        }
    }

    @Test
    fun `available source keeps the requested database selected`() {
        val source = UnifiedMoveInitialSource.KeePassDatabase(databaseId = 42L)

        assertEquals(
            "keepass:42",
            resolveUnifiedMoveInitialSourceKey(
                initialSource = source,
                availableSourceKeys = setOf("monica", "keepass:42"),
            ),
        )
    }

    @Test
    fun `missing source falls back to Monica and can resolve after loading`() {
        val source = UnifiedMoveInitialSource.BitwardenVault(vaultId = 52L)

        assertEquals(
            "monica",
            resolveUnifiedMoveInitialSourceKey(
                initialSource = source,
                availableSourceKeys = setOf("monica"),
            ),
        )
        assertEquals(
            "bitwarden:52",
            resolveUnifiedMoveInitialSourceKey(
                initialSource = source,
                availableSourceKeys = setOf("monica", "bitwarden:52"),
            ),
        )
    }
}
