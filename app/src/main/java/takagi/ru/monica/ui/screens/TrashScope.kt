package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.viewmodel.CategoryFilter

internal sealed interface TrashScopeFilter {
    data object All : TrashScopeFilter
    data object Local : TrashScopeFilter
    data class BitwardenVaultScope(val vaultId: Long) : TrashScopeFilter
    data class KeePassDatabaseScope(val databaseId: Long) : TrashScopeFilter
    data class MdbxDatabaseScope(val databaseId: Long) : TrashScopeFilter
}

internal val TrashScopeFilter.key: String
    get() = when (this) {
        TrashScopeFilter.All -> "all"
        TrashScopeFilter.Local -> "local"
        is TrashScopeFilter.BitwardenVaultScope -> "bitwarden_$vaultId"
        is TrashScopeFilter.KeePassDatabaseScope -> "keepass_$databaseId"
        is TrashScopeFilter.MdbxDatabaseScope -> "mdbx_$databaseId"
    }

/** Database identity remains valid while its display metadata is loading or unavailable. */
internal fun trashScopeFromKey(key: String?): TrashScopeFilter {
    val id = key?.substringAfter('_', "")?.toLongOrNull()
    return when {
        key == "local" -> TrashScopeFilter.Local
        key?.startsWith("bitwarden_") == true && id != null -> TrashScopeFilter.BitwardenVaultScope(id)
        key?.startsWith("keepass_") == true && id != null -> TrashScopeFilter.KeePassDatabaseScope(id)
        key?.startsWith("mdbx_") == true && id != null -> TrashScopeFilter.MdbxDatabaseScope(id)
        else -> TrashScopeFilter.All
    }
}

/** Folder, favorite, and unfiled views open the recycle bin for their owning database. */
internal fun CategoryFilter.toTrashScopeFilter(): TrashScopeFilter = when (this) {
    CategoryFilter.All, CategoryFilter.Archived, CategoryFilter.Starred, CategoryFilter.Uncategorized ->
        TrashScopeFilter.All
    CategoryFilter.Local, CategoryFilter.LocalOnly, CategoryFilter.LocalStarred,
    CategoryFilter.LocalUncategorized, is CategoryFilter.Custom -> TrashScopeFilter.Local
    is CategoryFilter.BitwardenVault -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is CategoryFilter.BitwardenFolderFilter -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is CategoryFilter.BitwardenVaultStarred -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is CategoryFilter.BitwardenVaultUncategorized -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is CategoryFilter.KeePassDatabase -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is CategoryFilter.KeePassGroupFilter -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is CategoryFilter.KeePassDatabaseStarred -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is CategoryFilter.KeePassDatabaseUncategorized -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is CategoryFilter.MdbxDatabase -> TrashScopeFilter.MdbxDatabaseScope(databaseId)
    is CategoryFilter.MdbxFolderFilter -> TrashScopeFilter.MdbxDatabaseScope(databaseId)
}

internal fun UnifiedCategoryFilterSelection.toTrashScopeFilter(
    fallbackScope: TrashScopeFilter = TrashScopeFilter.All
): TrashScopeFilter = when (this) {
    UnifiedCategoryFilterSelection.All -> TrashScopeFilter.All
    UnifiedCategoryFilterSelection.Local, UnifiedCategoryFilterSelection.LocalStarred,
    UnifiedCategoryFilterSelection.LocalUncategorized, is UnifiedCategoryFilterSelection.Custom ->
        TrashScopeFilter.Local
    is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> TrashScopeFilter.BitwardenVaultScope(vaultId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is UnifiedCategoryFilterSelection.KeePassGroupFilter -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> TrashScopeFilter.KeePassDatabaseScope(databaseId)
    is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> TrashScopeFilter.MdbxDatabaseScope(databaseId)
    is UnifiedCategoryFilterSelection.MdbxFolderFilter -> TrashScopeFilter.MdbxDatabaseScope(databaseId)
    UnifiedCategoryFilterSelection.Starred, UnifiedCategoryFilterSelection.Uncategorized -> fallbackScope
}
