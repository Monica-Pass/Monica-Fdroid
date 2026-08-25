package takagi.ru.monica.ui

internal data class KeePassGroupFilterIdentity(
    val databaseId: Long,
    val groupPath: String,
    val groupUuid: String? = null
) {
    fun matches(
        itemDatabaseId: Long?,
        itemGroupPath: String?,
        itemGroupUuid: String?
    ): Boolean {
        if (itemDatabaseId != databaseId) return false
        val expectedUuid = groupUuid.normalizedUuidOrNull()
        val actualUuid = itemGroupUuid.normalizedUuidOrNull()
        return if (expectedUuid != null && actualUuid != null) {
            expectedUuid == actualUuid
        } else {
            itemGroupPath == groupPath
        }
    }

    private fun String?.normalizedUuidOrNull(): String? {
        return this
            ?.trim()
            ?.trim('{', '}')
            ?.replace("-", "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }
}
