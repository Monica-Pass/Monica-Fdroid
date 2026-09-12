package takagi.ru.monica.ui.screens

import androidx.compose.runtime.saveable.mapSaver
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.viewmodel.NoteDraftStorageTarget

internal val NoteCategoryFilterSaver = mapSaver<NoteCategoryFilter>(
    save = { filter ->
        val state = encodeNoteCategoryFilter(filter)
        mapOf(
            "type" to state.type,
            "primaryId" to state.primaryId,
            "secondaryId" to state.secondaryId,
            "text" to state.text,
            "groupUuid" to state.groupUuid
        )
    },
    restore = { values ->
        decodeNoteCategoryFilter(
            SavedCategoryFilterState(
                type = values["type"] as? String ?: "all",
                primaryId = values["primaryId"] as? Long,
                secondaryId = values["secondaryId"] as? Long,
                text = values["text"] as? String,
                groupUuid = values["groupUuid"] as? String
            )
        )
    }
)

internal fun NoteCategoryFilter.bitwardenVaultIdForSync(): Long? = when (this) {
    is NoteCategoryFilter.BitwardenVault -> vaultId
    is NoteCategoryFilter.BitwardenFolderFilter -> vaultId
    is NoteCategoryFilter.BitwardenVaultStarred -> vaultId
    is NoteCategoryFilter.BitwardenVaultUncategorized -> vaultId
    else -> null
}

internal fun NoteCategoryFilter.toDraftStorageTarget(): NoteDraftStorageTarget = when (this) {
    NoteCategoryFilter.All,
    NoteCategoryFilter.Local,
    NoteCategoryFilter.Starred,
    NoteCategoryFilter.Uncategorized,
    NoteCategoryFilter.LocalStarred,
    NoteCategoryFilter.LocalUncategorized -> NoteDraftStorageTarget()
    is NoteCategoryFilter.Custom -> NoteDraftStorageTarget(categoryId = categoryId)
    is NoteCategoryFilter.BitwardenVault -> NoteDraftStorageTarget(bitwardenVaultId = vaultId)
    is NoteCategoryFilter.BitwardenFolderFilter -> NoteDraftStorageTarget(
        bitwardenVaultId = vaultId,
        bitwardenFolderId = folderId
    )
    is NoteCategoryFilter.BitwardenVaultStarred -> NoteDraftStorageTarget(bitwardenVaultId = vaultId)
    is NoteCategoryFilter.BitwardenVaultUncategorized -> NoteDraftStorageTarget(bitwardenVaultId = vaultId)
    is NoteCategoryFilter.KeePassDatabase -> NoteDraftStorageTarget(keepassDatabaseId = databaseId)
    is NoteCategoryFilter.KeePassGroupFilter -> NoteDraftStorageTarget(
        keepassDatabaseId = databaseId,
        keepassGroupPath = groupPath
    )
    is NoteCategoryFilter.KeePassDatabaseStarred -> NoteDraftStorageTarget(keepassDatabaseId = databaseId)
    is NoteCategoryFilter.KeePassDatabaseUncategorized -> NoteDraftStorageTarget(keepassDatabaseId = databaseId)
    is NoteCategoryFilter.MdbxDatabase -> NoteDraftStorageTarget(mdbxDatabaseId = databaseId)
}

internal fun encodeNoteCategoryFilter(filter: NoteCategoryFilter): SavedCategoryFilterState = when (filter) {
    NoteCategoryFilter.All -> SavedCategoryFilterState(type = "all")
    NoteCategoryFilter.Local -> SavedCategoryFilterState(type = "local")
    NoteCategoryFilter.Starred -> SavedCategoryFilterState(type = "starred")
    NoteCategoryFilter.Uncategorized -> SavedCategoryFilterState(type = "uncategorized")
    NoteCategoryFilter.LocalStarred -> SavedCategoryFilterState(type = "local_starred")
    NoteCategoryFilter.LocalUncategorized -> SavedCategoryFilterState(type = "local_uncategorized")
    is NoteCategoryFilter.Custom -> SavedCategoryFilterState(type = "custom", primaryId = filter.categoryId)
    is NoteCategoryFilter.BitwardenVault -> SavedCategoryFilterState(type = "bitwarden_vault", primaryId = filter.vaultId)
    is NoteCategoryFilter.BitwardenFolderFilter -> SavedCategoryFilterState(type = "bitwarden_folder", primaryId = filter.vaultId, text = filter.folderId)
    is NoteCategoryFilter.BitwardenVaultStarred -> SavedCategoryFilterState(type = "bitwarden_vault_starred", primaryId = filter.vaultId)
    is NoteCategoryFilter.BitwardenVaultUncategorized -> SavedCategoryFilterState(type = "bitwarden_vault_uncategorized", primaryId = filter.vaultId)
    is NoteCategoryFilter.KeePassDatabase -> SavedCategoryFilterState(type = "keepass_database", primaryId = filter.databaseId)
    is NoteCategoryFilter.KeePassGroupFilter -> SavedCategoryFilterState(
        type = "keepass_group",
        primaryId = filter.databaseId,
        text = filter.groupPath,
        groupUuid = filter.groupUuid
    )
    is NoteCategoryFilter.KeePassDatabaseStarred -> SavedCategoryFilterState(type = "keepass_database_starred", primaryId = filter.databaseId)
    is NoteCategoryFilter.KeePassDatabaseUncategorized -> SavedCategoryFilterState(type = "keepass_database_uncategorized", primaryId = filter.databaseId)
    is NoteCategoryFilter.MdbxDatabase -> SavedCategoryFilterState(type = "mdbx_database", primaryId = filter.databaseId)
}

internal fun decodeNoteCategoryFilter(state: SavedCategoryFilterState): NoteCategoryFilter {
    return when (state.type) {
        "all" -> NoteCategoryFilter.All
        "local" -> NoteCategoryFilter.Local
        "starred" -> NoteCategoryFilter.Starred
        "uncategorized" -> NoteCategoryFilter.Uncategorized
        "local_starred" -> NoteCategoryFilter.LocalStarred
        "local_uncategorized" -> NoteCategoryFilter.LocalUncategorized
        "custom" -> state.primaryId?.let { NoteCategoryFilter.Custom(it) } ?: NoteCategoryFilter.All
        "bitwarden_vault" -> state.primaryId?.let { NoteCategoryFilter.BitwardenVault(it) } ?: NoteCategoryFilter.All
        "bitwarden_folder" -> {
            val vaultId = state.primaryId
            val folderId = state.text
            if (vaultId != null && !folderId.isNullOrBlank()) NoteCategoryFilter.BitwardenFolderFilter(folderId, vaultId) else NoteCategoryFilter.All
        }
        "bitwarden_vault_starred" -> state.primaryId?.let { NoteCategoryFilter.BitwardenVaultStarred(it) } ?: NoteCategoryFilter.All
        "bitwarden_vault_uncategorized" -> state.primaryId?.let { NoteCategoryFilter.BitwardenVaultUncategorized(it) } ?: NoteCategoryFilter.All
        "keepass_database" -> state.primaryId?.let { NoteCategoryFilter.KeePassDatabase(it) } ?: NoteCategoryFilter.All
        "keepass_group" -> {
            val databaseId = state.primaryId
            val groupPath = state.text
            if (databaseId != null && !groupPath.isNullOrBlank()) {
                NoteCategoryFilter.KeePassGroupFilter(databaseId, groupPath, state.groupUuid)
            } else {
                NoteCategoryFilter.All
            }
        }
        "keepass_database_starred" -> state.primaryId?.let { NoteCategoryFilter.KeePassDatabaseStarred(it) } ?: NoteCategoryFilter.All
        "keepass_database_uncategorized" -> state.primaryId?.let { NoteCategoryFilter.KeePassDatabaseUncategorized(it) } ?: NoteCategoryFilter.All
        "mdbx_database" -> state.primaryId?.let { NoteCategoryFilter.MdbxDatabase(it) } ?: NoteCategoryFilter.All
        else -> NoteCategoryFilter.All
    }
}
