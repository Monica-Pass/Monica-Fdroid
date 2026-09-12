package takagi.ru.monica.notes.domain

import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.resolveOwnership

/** Resolve ownership once per snapshot; group UUIDs are only prepared when that filter needs them. */
internal class NoteScopeIndex(private val items: List<SecureItem>) {
    private val owners = Array(items.size) { items[it].resolveOwnership() }
    private val groupUuids by lazy { Array(items.size) { normalizeUuid(items[it].keepassGroupUuid) } }

    private fun normalizeUuid(value: String?): String? = value?.trim()?.trim('{', '}')
        ?.replace("-", "")?.lowercase()?.takeIf { it.isNotBlank() }

    fun select(scope: NoteCategoryFilter): IntArray {
        if (scope == NoteCategoryFilter.All) return IntArray(items.size) { it }
        val expectedUuid = (scope as? NoteCategoryFilter.KeePassGroupFilter)?.groupUuid?.let(::normalizeUuid)
        val uuids = if (expectedUuid == null) null else groupUuids
        val selected = IntArray(items.size)
        var count = 0
        for (index in items.indices) {
            val item = items[index]
            val owner = owners[index]
            val matches = when (scope) {
                NoteCategoryFilter.All -> true
                NoteCategoryFilter.Local -> owner is SecureItemOwnership.MonicaLocal
                NoteCategoryFilter.Starred -> item.isFavorite
                NoteCategoryFilter.Uncategorized -> item.categoryId == null
                NoteCategoryFilter.LocalStarred -> owner is SecureItemOwnership.MonicaLocal && item.isFavorite
                NoteCategoryFilter.LocalUncategorized -> owner is SecureItemOwnership.MonicaLocal && item.categoryId == null
                is NoteCategoryFilter.Custom -> owner is SecureItemOwnership.MonicaLocal && item.categoryId == scope.categoryId
                is NoteCategoryFilter.BitwardenVault -> owner is SecureItemOwnership.Bitwarden && owner.vaultId == scope.vaultId
                is NoteCategoryFilter.BitwardenFolderFilter -> owner is SecureItemOwnership.Bitwarden && owner.vaultId == scope.vaultId && item.bitwardenFolderId == scope.folderId
                is NoteCategoryFilter.BitwardenVaultStarred -> owner is SecureItemOwnership.Bitwarden && owner.vaultId == scope.vaultId && item.isFavorite
                is NoteCategoryFilter.BitwardenVaultUncategorized -> owner is SecureItemOwnership.Bitwarden && owner.vaultId == scope.vaultId && item.bitwardenFolderId == null
                is NoteCategoryFilter.KeePassDatabase -> owner is SecureItemOwnership.KeePass && owner.databaseId == scope.databaseId
                is NoteCategoryFilter.KeePassGroupFilter -> owner is SecureItemOwnership.KeePass && owner.databaseId == scope.databaseId &&
                    if (expectedUuid != null && uuids?.get(index) != null) uuids[index] == expectedUuid
                    else item.keepassGroupPath == scope.groupPath
                is NoteCategoryFilter.KeePassDatabaseStarred -> owner is SecureItemOwnership.KeePass && owner.databaseId == scope.databaseId && item.isFavorite
                is NoteCategoryFilter.KeePassDatabaseUncategorized -> owner is SecureItemOwnership.KeePass && owner.databaseId == scope.databaseId && item.keepassGroupPath.isNullOrBlank()
                is NoteCategoryFilter.MdbxDatabase -> owner is SecureItemOwnership.Mdbx && owner.databaseId == scope.databaseId
            }
            if (matches) selected[count++] = index
        }
        return selected.copyOf(count)
    }
}
