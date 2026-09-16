package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection as Selection
import takagi.ru.monica.viewmodel.CategoryFilter as Filter

class TrashScopeTest {
    @Test fun passwordFiltersOpenTheRecycleBinOfTheirOwningDatabase() {
        val cases = listOf(
            Filter.All to "all", Filter.Archived to "all", Filter.Starred to "all", Filter.Uncategorized to "all",
            Filter.Local to "local", Filter.LocalOnly to "local", Filter.LocalStarred to "local",
            Filter.LocalUncategorized to "local", Filter.Custom(9) to "local",
            Filter.BitwardenVault(11) to "bitwarden_11",
            Filter.BitwardenFolderFilter("folder", 11) to "bitwarden_11",
            Filter.BitwardenVaultStarred(11) to "bitwarden_11",
            Filter.BitwardenVaultUncategorized(11) to "bitwarden_11",
            Filter.KeePassDatabase(22) to "keepass_22",
            Filter.KeePassGroupFilter(22, "Work/Email") to "keepass_22",
            Filter.KeePassDatabaseStarred(22) to "keepass_22",
            Filter.KeePassDatabaseUncategorized(22) to "keepass_22",
            Filter.MdbxDatabase(33) to "mdbx_33",
            Filter.MdbxFolderFilter(33, "folder") to "mdbx_33",
        )
        cases.forEach { (filter, expected) -> assertEquals(filter.toString(), expected, filter.toTrashScopeFilter().key) }
    }

    @Test fun vaultAndRecycleBinMenusUseTheSameDatabaseKeys() {
        val cases = listOf(
            Selection.All to "all", Selection.Starred to "all", Selection.Uncategorized to "all",
            Selection.Local to "local", Selection.LocalStarred to "local",
            Selection.LocalUncategorized to "local", Selection.Custom(9) to "local",
            Selection.BitwardenVaultFilter(11) to "bitwarden_11",
            Selection.BitwardenFolderFilter(11, "folder") to "bitwarden_11",
            Selection.BitwardenVaultStarredFilter(11) to "bitwarden_11",
            Selection.BitwardenVaultUncategorizedFilter(11) to "bitwarden_11",
            Selection.KeePassDatabaseFilter(22) to "keepass_22",
            Selection.KeePassGroupFilter(22, "Work/Email") to "keepass_22",
            Selection.KeePassDatabaseStarredFilter(22) to "keepass_22",
            Selection.KeePassDatabaseUncategorizedFilter(22) to "keepass_22",
            Selection.MdbxDatabaseFilter(33) to "mdbx_33",
            Selection.MdbxFolderFilter(33, "folder") to "mdbx_33",
        )
        cases.forEach { (filter, expected) -> assertEquals(filter.toString(), expected, filter.toTrashScopeFilter().key) }
    }

    @Test fun savedDatabaseIdentityDoesNotRequireDatabaseDisplayMetadata() {
        val scopes = listOf(
            TrashScopeFilter.All, TrashScopeFilter.Local,
            TrashScopeFilter.BitwardenVaultScope(11), TrashScopeFilter.KeePassDatabaseScope(22),
            TrashScopeFilter.MdbxDatabaseScope(Long.MAX_VALUE)
        )
        scopes.forEach { scope -> assertEquals(scope, trashScopeFromKey(scope.key)) }
        assertEquals(TrashScopeFilter.All, trashScopeFromKey(null))
    }
}
