package takagi.ru.monica.ui.components

sealed interface UnifiedMoveInitialSource {
    val sourceKey: String

    data object MonicaLocal : UnifiedMoveInitialSource {
        override val sourceKey: String = "monica"
    }

    data class KeePassDatabase(val databaseId: Long) : UnifiedMoveInitialSource {
        override val sourceKey: String = "keepass:$databaseId"
    }

    data class MdbxDatabase(val databaseId: Long) : UnifiedMoveInitialSource {
        override val sourceKey: String = "mdbx:$databaseId"
    }

    data class BitwardenVault(val vaultId: Long) : UnifiedMoveInitialSource {
        override val sourceKey: String = "bitwarden:$vaultId"
    }
}

fun UnifiedCategoryFilterSelection.toUnifiedMoveInitialSource(): UnifiedMoveInitialSource {
    return when (this) {
        UnifiedCategoryFilterSelection.All,
        UnifiedCategoryFilterSelection.Local,
        UnifiedCategoryFilterSelection.Starred,
        UnifiedCategoryFilterSelection.Uncategorized,
        UnifiedCategoryFilterSelection.LocalStarred,
        UnifiedCategoryFilterSelection.LocalUncategorized,
        is UnifiedCategoryFilterSelection.Custom -> UnifiedMoveInitialSource.MonicaLocal

        is UnifiedCategoryFilterSelection.BitwardenVaultFilter ->
            UnifiedMoveInitialSource.BitwardenVault(vaultId)
        is UnifiedCategoryFilterSelection.BitwardenFolderFilter ->
            UnifiedMoveInitialSource.BitwardenVault(vaultId)
        is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter ->
            UnifiedMoveInitialSource.BitwardenVault(vaultId)
        is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter ->
            UnifiedMoveInitialSource.BitwardenVault(vaultId)

        is UnifiedCategoryFilterSelection.KeePassDatabaseFilter ->
            UnifiedMoveInitialSource.KeePassDatabase(databaseId)
        is UnifiedCategoryFilterSelection.KeePassGroupFilter ->
            UnifiedMoveInitialSource.KeePassDatabase(databaseId)
        is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter ->
            UnifiedMoveInitialSource.KeePassDatabase(databaseId)
        is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter ->
            UnifiedMoveInitialSource.KeePassDatabase(databaseId)

        is UnifiedCategoryFilterSelection.MdbxDatabaseFilter ->
            UnifiedMoveInitialSource.MdbxDatabase(databaseId)
        is UnifiedCategoryFilterSelection.MdbxFolderFilter ->
            UnifiedMoveInitialSource.MdbxDatabase(databaseId)
    }
}

internal fun resolveUnifiedMoveInitialSourceKey(
    initialSource: UnifiedMoveInitialSource,
    availableSourceKeys: Set<String>,
): String {
    return initialSource.sourceKey.takeIf(availableSourceKeys::contains)
        ?: UnifiedMoveInitialSource.MonicaLocal.sourceKey
}
