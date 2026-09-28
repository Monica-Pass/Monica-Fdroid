package takagi.ru.monica.repository

import takagi.ru.monica.data.PasswordEntry

/** A disposable list projection, never a login payload or an autofill credential. */
internal object MdbxUnknownEntry {
    const val LOGIN_TYPE_PREFIX = "MDBX_UNKNOWN:"
    private val supportedTypes = setOf(
        "login", "note", "totp", "card", "document-ref", "billing-address", "payment-account",
        "passkey", "api-token", "steam-mafile", "steam_mafile"
    )

    fun isUnknown(type: String): Boolean = type !in supportedTypes
    fun isProjection(entry: PasswordEntry): Boolean = entry.loginType.startsWith(LOGIN_TYPE_PREFIX)
    fun projectsAsPassword(type: String): Boolean = type == "login" || isUnknown(type)
    fun requireEditable(entry: PasswordEntry) {
        require(!isProjection(entry)) { "This MDBX item type is available for viewing only" }
    }

    fun project(databaseId: Long, stored: MdbxStoredVaultEntry, existing: PasswordEntry?): PasswordEntry =
        PasswordEntry(
            id = existing?.id ?: 0L,
            title = stored.title,
            website = "", username = "", password = "", notes = "",
            mdbxDatabaseId = databaseId,
            mdbxFolderId = stored.collectionId,
            replicaGroupId = stored.entryId,
            loginType = LOGIN_TYPE_PREFIX + stored.entryType,
            createdAt = existing?.createdAt ?: java.util.Date(),
            updatedAt = existing?.updatedAt ?: java.util.Date(),
            isFavorite = existing?.isFavorite ?: false,
            sortOrder = existing?.sortOrder ?: 0,
            isDeleted = stored.deleted
        )
}
