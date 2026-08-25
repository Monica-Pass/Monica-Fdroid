package takagi.ru.monica.repository

import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.utils.KeePassCustomFieldData
import takagi.ru.monica.utils.KeePassRestoreTarget
import takagi.ru.monica.keepass.KeePassProjectionKind
import takagi.ru.monica.keepass.KeePassProjectionRefreshDecision
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassNativeSearchOptions
import takagi.ru.monica.keepass.KeePassDatabaseSettingsUpdate
import takagi.ru.monica.keepass.KeePassKeyFileChangeMode
import takagi.ru.monica.keepass.KeePassNativeGroupUpdate
import java.time.Instant
import java.util.UUID

class KeePassCompatibilityBridge(
    private val workspaceRepository: KeePassWorkspaceRepository
) {

    suspend fun upsertLegacyPasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>,
        resolvePassword: (PasswordEntry) -> String,
        forceSyncWrite: Boolean = false,
        customFieldsByEntryId: Map<Long, List<KeePassCustomFieldData>> = emptyMap()
    ) = workspaceRepository.addOrUpdatePasswordEntries(
        databaseId = databaseId,
        entries = entries,
        resolvePassword = resolvePassword,
        forceSyncWrite = forceSyncWrite,
        customFieldsByEntryId = customFieldsByEntryId
    )

    suspend fun upsertLegacySecureItems(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.addOrUpdateSecureItems(
        databaseId = databaseId,
        items = items,
        forceSyncWrite = forceSyncWrite
    )

    suspend fun loadLegacyWorkspace(
        databaseId: Long,
        allowedSecureItemTypes: Set<ItemType>? = null
    ) = workspaceRepository.loadWorkspace(
        databaseId = databaseId,
        allowedSecureItemTypes = allowedSecureItemTypes
    )

    internal suspend fun legacyProjectionRefreshDecision(
        databaseId: Long,
        kind: KeePassProjectionKind,
        forceRefresh: Boolean = false
    ): Result<KeePassProjectionRefreshDecision> = workspaceRepository.projectionRefreshDecision(
        databaseId = databaseId,
        kind = kind,
        forceRefresh = forceRefresh
    )

    internal fun markLegacyProjectionIndexed(
        databaseId: Long,
        revisionToken: String,
        kinds: Set<KeePassProjectionKind>
    ) = workspaceRepository.markProjectionIndexed(databaseId, revisionToken, kinds)

    internal suspend fun openNativeBrowser(databaseId: Long) =
        workspaceRepository.openNativeBrowser(databaseId)

    internal fun isDatabaseReadOnly(databaseId: Long): Boolean =
        workspaceRepository.isDatabaseReadOnly(databaseId)

    internal fun setDatabaseReadOnly(databaseId: Long, readOnly: Boolean) =
        workspaceRepository.setDatabaseReadOnly(databaseId, readOnly)

    internal fun lockDatabase(databaseId: Long) =
        workspaceRepository.lockDatabase(databaseId)

    internal suspend fun readNativeDatabaseSettings(databaseId: Long) =
        workspaceRepository.readNativeDatabaseSettings(databaseId)

    internal suspend fun updateNativeDatabaseSettings(
        databaseId: Long,
        update: KeePassDatabaseSettingsUpdate
    ) = workspaceRepository.updateNativeDatabaseSettings(databaseId, update)

    internal suspend fun changeMasterCredentials(
        databaseId: Long,
        newPassword: String,
        keyFileMode: KeePassKeyFileChangeMode,
        replacementKeyFileUri: android.net.Uri?,
        keepInternalKeyFileCopy: Boolean
    ) = workspaceRepository.changeMasterCredentials(
        databaseId = databaseId,
        newPassword = newPassword,
        keyFileMode = keyFileMode,
        replacementKeyFileUri = replacementKeyFileUri,
        keepInternalKeyFileCopy = keepInternalKeyFileCopy
    )

    internal suspend fun searchNativeEntries(
        databaseId: Long,
        options: KeePassNativeSearchOptions,
        now: Instant = Instant.now()
    ) = workspaceRepository.searchNativeEntries(databaseId, options, now)

    internal suspend fun replaceNativeEntryFields(
        databaseId: Long,
        entryUuid: UUID,
        fields: List<KeePassFieldChange>,
        expectedRevisionToken: String
    ) = workspaceRepository.replaceNativeEntryFields(
        databaseId,
        entryUuid,
        fields,
        expectedRevisionToken
    )

    internal suspend fun restoreNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ) = workspaceRepository.restoreNativeEntryHistory(
        databaseId,
        entryUuid,
        historyIndex,
        expectedRevisionToken
    )

    internal suspend fun deleteNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ) = workspaceRepository.deleteNativeEntryHistory(
        databaseId,
        entryUuid,
        historyIndex,
        expectedRevisionToken
    )

    internal suspend fun createNativeGroup(
        databaseId: Long,
        parentGroupUuid: UUID,
        name: String,
        expectedRevisionToken: String,
        properties: KeePassNativeGroupUpdate? = null,
    ) = workspaceRepository.createNativeGroup(
        databaseId,
        parentGroupUuid,
        name,
        expectedRevisionToken,
        properties,
    )

    internal suspend fun renameNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        newName: String,
        expectedRevisionToken: String
    ) = workspaceRepository.renameNativeGroup(
        databaseId,
        groupUuid,
        newName,
        expectedRevisionToken
    )

    internal suspend fun moveNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        targetParentGroupUuid: UUID,
        expectedRevisionToken: String
    ) = workspaceRepository.moveNativeGroup(
        databaseId,
        groupUuid,
        targetParentGroupUuid,
        expectedRevisionToken
    )

    internal suspend fun deleteNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        expectedRevisionToken: String
    ) = workspaceRepository.deleteNativeGroup(
        databaseId,
        groupUuid,
        expectedRevisionToken
    )

    suspend fun readLegacyPasswordEntries(databaseId: Long) = workspaceRepository.readPasswordEntries(databaseId)

    suspend fun readLegacySecureItems(
        databaseId: Long,
        allowedTypes: Set<ItemType>? = null
    ) = workspaceRepository.readSecureItems(databaseId, allowedTypes)

    suspend fun readLegacyPasskeys(databaseId: Long) =
        workspaceRepository.readPasskeyEntries(databaseId)

    suspend fun listLegacyGroups(
        databaseId: Long,
        includeRecycleBin: Boolean = false
    ) = workspaceRepository.listGroups(databaseId, includeRecycleBin)

    suspend fun createLegacyGroup(
        databaseId: Long,
        groupName: String,
        parentPath: String? = null
    ) = workspaceRepository.createGroup(databaseId, groupName, parentPath)

    suspend fun renameLegacyGroup(
        databaseId: Long,
        groupPath: String,
        newName: String
    ) = workspaceRepository.renameGroup(databaseId, groupPath, newName)

    suspend fun deleteLegacyGroup(
        databaseId: Long,
        groupPath: String
    ) = workspaceRepository.deleteGroup(databaseId, groupPath)

    suspend fun moveLegacyGroup(
        sourceDatabaseId: Long,
        groupPath: String,
        targetDatabaseId: Long,
        targetParentPath: String? = null
    ) = workspaceRepository.moveGroup(
        sourceDatabaseId = sourceDatabaseId,
        groupPath = groupPath,
        targetDatabaseId = targetDatabaseId,
        targetParentPath = targetParentPath
    )

    suspend fun updateLegacyPasswordEntry(
        databaseId: Long,
        entry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ) = workspaceRepository.updatePasswordEntry(databaseId, entry, resolvePassword, customFields)

    suspend fun upsertLegacyPasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ) = workspaceRepository.addOrUpdatePasskeys(databaseId, passkeys)

    suspend fun deleteLegacyPasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>
    ) = workspaceRepository.deletePasswordEntries(databaseId, entries)

    suspend fun updateLegacyPasskey(
        databaseId: Long,
        passkey: PasskeyEntry
    ) = workspaceRepository.updatePasskey(databaseId, passkey)

    suspend fun deleteLegacyPasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ) = workspaceRepository.deletePasskeys(databaseId, passkeys)

    suspend fun moveLegacyPasswordEntriesToRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.movePasswordEntriesToRecycleBin(databaseId, entries, forceSyncWrite)

    suspend fun restoreLegacyPasswordEntriesFromRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.restorePasswordEntriesFromRecycleBin(databaseId, entries, forceSyncWrite)

    suspend fun resolveLegacyRestoreGroupPathForPassword(
        databaseId: Long,
        entry: PasswordEntry
    ) = workspaceRepository.resolveRestoreGroupPathForPassword(databaseId, entry)

    suspend fun resolveLegacyRestoreTargetForPassword(
        databaseId: Long,
        entry: PasswordEntry
    ): Result<KeePassRestoreTarget> = workspaceRepository.resolveRestoreTargetForPassword(databaseId, entry)

    suspend fun syncLegacyRemoteDatabase(databaseId: Long) =
        workspaceRepository.syncRemoteDatabase(databaseId)

    suspend fun updateLegacySecureItem(
        databaseId: Long,
        item: SecureItem
    ) = workspaceRepository.updateSecureItem(databaseId, item)

    suspend fun deleteLegacySecureItems(
        databaseId: Long,
        items: List<SecureItem>
    ) = workspaceRepository.deleteSecureItems(databaseId, items)

    suspend fun moveLegacySecureItemsToRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.moveSecureItemsToRecycleBin(databaseId, items, forceSyncWrite)

    suspend fun restoreLegacySecureItemsFromRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.restoreSecureItemsFromRecycleBin(databaseId, items, forceSyncWrite)

    suspend fun resolveLegacyRestoreGroupPathForSecureItem(
        databaseId: Long,
        item: SecureItem
    ) = workspaceRepository.resolveRestoreGroupPathForSecureItem(databaseId, item)

    suspend fun resolveLegacyRestoreTargetForSecureItem(
        databaseId: Long,
        item: SecureItem
    ): Result<KeePassRestoreTarget> = workspaceRepository.resolveRestoreTargetForSecureItem(databaseId, item)
}
