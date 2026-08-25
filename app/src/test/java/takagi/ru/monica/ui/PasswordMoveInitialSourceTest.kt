package takagi.ru.monica.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.ui.components.UnifiedMoveInitialSource
import takagi.ru.monica.viewmodel.CategoryFilter

class PasswordMoveInitialSourceTest {

    @Test
    fun `password database filters preserve their current source`() {
        assertEquals(
            UnifiedMoveInitialSource.KeePassDatabase(databaseId = 1L),
            CategoryFilter.KeePassGroupFilter(
                databaseId = 1L,
                groupPath = "Root/Work",
            ).toUnifiedMoveInitialSource(),
        )
        assertEquals(
            UnifiedMoveInitialSource.MdbxDatabase(databaseId = 2L),
            CategoryFilter.MdbxFolderFilter(
                databaseId = 2L,
                folderId = "folder",
            ).toUnifiedMoveInitialSource(),
        )
        assertEquals(
            UnifiedMoveInitialSource.BitwardenVault(vaultId = 3L),
            CategoryFilter.BitwardenFolderFilter(
                folderId = "folder",
                vaultId = 3L,
            ).toUnifiedMoveInitialSource(),
        )
    }

    @Test
    fun `password global and local filters use Monica`() {
        val filters = listOf(
            CategoryFilter.All,
            CategoryFilter.Archived,
            CategoryFilter.Local,
            CategoryFilter.LocalOnly,
            CategoryFilter.Starred,
            CategoryFilter.Uncategorized,
            CategoryFilter.LocalStarred,
            CategoryFilter.LocalUncategorized,
            CategoryFilter.Custom(categoryId = 9L),
        )

        filters.forEach { filter ->
            assertEquals(
                UnifiedMoveInitialSource.MonicaLocal,
                filter.toUnifiedMoveInitialSource(),
            )
        }
    }
}
