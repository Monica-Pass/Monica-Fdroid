package takagi.ru.monica.data

data class VaultOverviewTrashCount(
    val bitwardenVaultId: Long?,
    val keepassDatabaseId: Long?,
    val mdbxDatabaseId: Long?,
    val count: Int,
)
