package takagi.ru.monica.repository

import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import java.util.Date

internal fun SecureItem.asMdbxBatchCopy(
    databaseId: Long,
    folderId: String?,
    storedItemData: String = itemData,
    now: Date = Date()
): SecureItem = copy(
    id = 0L,
    sortOrder = 0,
    itemData = storedItemData,
    categoryId = null,
    keepassDatabaseId = null,
    keepassGroupPath = null,
    keepassEntryUuid = null,
    keepassGroupUuid = null,
    mdbxDatabaseId = databaseId,
    mdbxFolderId = folderId,
    isDeleted = false,
    deletedAt = null,
    replicaGroupId = null,
    bitwardenVaultId = null,
    bitwardenCipherId = null,
    bitwardenFolderId = null,
    bitwardenRevisionDate = null,
    bitwardenLocalModified = false,
    syncStatus = "NONE",
    createdAt = now,
    updatedAt = now
)

internal fun SecureItem.asMdbxBatchMove(
    databaseId: Long,
    folderId: String?,
    storedItemData: String = itemData,
    now: Date = Date()
): SecureItem = copy(
    itemData = storedItemData,
    categoryId = null,
    keepassDatabaseId = null,
    keepassGroupPath = null,
    keepassEntryUuid = null,
    keepassGroupUuid = null,
    mdbxDatabaseId = databaseId,
    mdbxFolderId = folderId,
    isDeleted = false,
    deletedAt = null,
    bitwardenVaultId = null,
    bitwardenCipherId = null,
    bitwardenFolderId = null,
    bitwardenRevisionDate = null,
    bitwardenLocalModified = false,
    syncStatus = "NONE",
    updatedAt = now
)

internal fun findMdbxReplicaTargetConflictIds(
    selectedItems: List<SecureItem>,
    activeItems: List<SecureItem>,
    databaseId: Long,
    folderId: String?
): Set<Long> {
    if (selectedItems.isEmpty()) return emptySet()

    val plannedReplicaKeys = mutableSetOf<Pair<ItemType, String>>()
    val conflicts = linkedSetOf<Long>()
    selectedItems.forEach { item ->
        val replicaGroupId = item.replicaGroupId?.takeIf(String::isNotBlank)
            ?: return@forEach
        val replicaKey = item.itemType to replicaGroupId
        val occupiedByAnotherItem = activeItems.any { candidate ->
            candidate.id != item.id &&
                !candidate.isDeleted &&
                candidate.itemType == item.itemType &&
                candidate.replicaGroupId == replicaGroupId &&
                candidate.mdbxDatabaseId == databaseId &&
                candidate.mdbxFolderId.orEmpty() == folderId.orEmpty()
        }
        if (occupiedByAnotherItem || !plannedReplicaKeys.add(replicaKey)) {
            conflicts += item.id
        }
    }
    return conflicts
}
