package takagi.ru.monica.notes.domain

internal sealed interface NoteCategoryFilter {
    data object All : NoteCategoryFilter
    data object Local : NoteCategoryFilter
    data object Starred : NoteCategoryFilter
    data object Uncategorized : NoteCategoryFilter
    data object LocalStarred : NoteCategoryFilter
    data object LocalUncategorized : NoteCategoryFilter
    data class Custom(val categoryId: Long) : NoteCategoryFilter
    data class BitwardenVault(val vaultId: Long) : NoteCategoryFilter
    data class BitwardenFolderFilter(val folderId: String, val vaultId: Long) : NoteCategoryFilter
    data class BitwardenVaultStarred(val vaultId: Long) : NoteCategoryFilter
    data class BitwardenVaultUncategorized(val vaultId: Long) : NoteCategoryFilter
    data class KeePassDatabase(val databaseId: Long) : NoteCategoryFilter
    data class KeePassGroupFilter(
        val databaseId: Long,
        val groupPath: String,
        val groupUuid: String? = null
    ) : NoteCategoryFilter
    data class KeePassDatabaseStarred(val databaseId: Long) : NoteCategoryFilter
    data class KeePassDatabaseUncategorized(val databaseId: Long) : NoteCategoryFilter
    data class MdbxDatabase(val databaseId: Long) : NoteCategoryFilter
}
