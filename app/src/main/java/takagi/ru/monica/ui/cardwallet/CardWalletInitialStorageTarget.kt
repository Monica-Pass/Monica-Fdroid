package takagi.ru.monica.ui.cardwallet

import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.utils.RememberedStorageTarget
import takagi.ru.monica.utils.SavedCategoryFilterState

internal fun resolveCardWalletInitialStorageTarget(
    explicitTarget: StorageTarget?,
    filterState: SavedCategoryFilterState,
    rememberedTarget: RememberedStorageTarget
): StorageTarget {
    return explicitTarget
        ?: filterState.toCardWalletStorageTargetOrNull()
        ?: rememberedTarget.toStorageTarget()
}

private fun SavedCategoryFilterState.toCardWalletStorageTargetOrNull(): StorageTarget? {
    return when (type) {
        "local", "local_starred", "local_uncategorized" -> StorageTarget.MonicaLocal(null)
        "custom" -> primaryId?.let { StorageTarget.MonicaLocal(it) }
        "bitwarden_vault", "bitwarden_vault_starred", "bitwarden_vault_uncategorized" ->
            primaryId?.let { StorageTarget.Bitwarden(it, null) }
        "bitwarden_folder" -> storageIdAndPathOrNull()?.let { (vaultId, folderId) ->
            StorageTarget.Bitwarden(vaultId, folderId)
        }
        "keepass_database", "keepass_database_starred", "keepass_database_uncategorized" ->
            primaryId?.let { StorageTarget.KeePass(it, null) }
        "keepass_group" -> storageIdAndPathOrNull()?.let { (databaseId, groupPath) ->
            StorageTarget.KeePass(databaseId, groupPath)
        }
        "mdbx_database" -> primaryId?.let { StorageTarget.Mdbx(it) }
        "mdbx_folder" -> storageIdAndPathOrNull()?.let { (databaseId, folderId) ->
            StorageTarget.Mdbx(databaseId, folderId)
        }
        else -> null
    }
}

private fun SavedCategoryFilterState.storageIdAndPathOrNull(): Pair<Long, String>? {
    val storageId = primaryId ?: return null
    val path = text?.takeIf(String::isNotBlank) ?: return null
    return storageId to path
}

private fun RememberedStorageTarget.toStorageTarget(): StorageTarget {
    return when {
        bitwardenVaultId != null -> StorageTarget.Bitwarden(bitwardenVaultId, bitwardenFolderId)
        keepassDatabaseId != null -> StorageTarget.KeePass(keepassDatabaseId, keepassGroupPath)
        mdbxDatabaseId != null -> StorageTarget.Mdbx(mdbxDatabaseId, mdbxFolderId)
        else -> StorageTarget.MonicaLocal(categoryId)
    }
}
