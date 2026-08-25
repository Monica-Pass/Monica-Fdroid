package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.util.UUID
import okio.ByteString

internal data class KeePassIntegrityReport(
    val groupCount: Int,
    val entryCount: Int,
    val duplicateGroupUuids: Map<UUID, Int>,
    val duplicateEntryUuids: Map<UUID, Int>,
    val missingBinaryHashes: Set<ByteString>,
    val unreferencedBinaryHashes: Set<ByteString>,
    val missingCustomIconUuids: Set<UUID>,
    val unreferencedCustomIconUuids: Set<UUID>,
    val invalidRecycleBinUuid: UUID?,
    val invalidTemplateGroupUuid: UUID?
) {
    val hasProblems: Boolean
        get() = duplicateGroupUuids.isNotEmpty() ||
            duplicateEntryUuids.isNotEmpty() ||
            missingBinaryHashes.isNotEmpty() ||
            unreferencedBinaryHashes.isNotEmpty() ||
            missingCustomIconUuids.isNotEmpty() ||
            unreferencedCustomIconUuids.isNotEmpty() ||
            invalidRecycleBinUuid != null ||
            invalidTemplateGroupUuid != null
}

internal enum class KeePassMaintenanceActionType {
    REASSIGN_DUPLICATE_UUID,
    REMOVE_UNREFERENCED_BINARY,
    REMOVE_UNREFERENCED_CUSTOM_ICON,
    CLEAR_INVALID_RECYCLE_BIN,
    CLEAR_INVALID_TEMPLATE_GROUP
}

internal data class KeePassMaintenanceAction(
    val type: KeePassMaintenanceActionType,
    val objectId: String,
    val detail: String
)

internal data class KeePassMaintenanceOptions(
    val repairDuplicateUuids: Boolean = true,
    val removeUnreferencedBinaries: Boolean = true,
    val removeUnreferencedCustomIcons: Boolean = true,
    val clearInvalidGroupReferences: Boolean = true
)

internal data class KeePassMaintenanceResult(
    val database: KeePassDatabase,
    val before: KeePassIntegrityReport,
    val after: KeePassIntegrityReport,
    val actions: List<KeePassMaintenanceAction>
)

internal data class KeePassMaintenanceExecution(
    val result: KeePassMaintenanceResult,
    val recoveryRecord: KeePassRecoveryRecord
)

internal object KeePassDatabaseMaintenance {
    fun inspect(database: KeePassDatabase): KeePassIntegrityReport {
        val groups = collectGroups(database.content.group)
        val entries = collectEntries(database.content.group)
        val groupUuids = groups.map(Group::uuid).toSet()
        val binaryReferences = linkedSetOf<ByteString>()
        val customIconReferences = linkedSetOf<UUID>()
        groups.forEach { group -> group.customIconUuid?.let(customIconReferences::add) }
        entries.forEach { entry -> collectEntryResources(entry, binaryReferences, customIconReferences) }
        val binaryPool = database.binaries
        val customIconPool = database.content.meta.customIcons
        val recycleBinUuid = database.content.meta.recycleBinUuid
        val templateGroupUuid = database.content.meta.entryTemplatesGroup
        return KeePassIntegrityReport(
            groupCount = groups.size,
            entryCount = entries.size,
            duplicateGroupUuids = duplicateCounts(groups.map(Group::uuid)),
            duplicateEntryUuids = duplicateCounts(entries.map(Entry::uuid)),
            missingBinaryHashes = binaryReferences - binaryPool.keys,
            unreferencedBinaryHashes = binaryPool.keys - binaryReferences,
            missingCustomIconUuids = customIconReferences - customIconPool.keys,
            unreferencedCustomIconUuids = customIconPool.keys - customIconReferences,
            invalidRecycleBinUuid = recycleBinUuid?.takeIf { it !in groupUuids },
            invalidTemplateGroupUuid = templateGroupUuid?.takeIf { it !in groupUuids }
        )
    }

    fun repair(
        database: KeePassDatabase,
        options: KeePassMaintenanceOptions = KeePassMaintenanceOptions()
    ): KeePassMaintenanceResult {
        val before = inspect(database)
        val actions = mutableListOf<KeePassMaintenanceAction>()
        var updated = database

        if (options.repairDuplicateUuids &&
            (before.duplicateGroupUuids.isNotEmpty() || before.duplicateEntryUuids.isNotEmpty())
        ) {
            val usedGroupUuids = mutableSetOf<UUID>()
            val usedEntryUuids = mutableSetOf<UUID>()
            val rewrittenRoot = rewriteDuplicateUuids(
                group = updated.content.group,
                usedGroupUuids = usedGroupUuids,
                usedEntryUuids = usedEntryUuids,
                actions = actions
            )
            updated = updated.modifyContent { copy(group = rewrittenRoot) }
        }

        val afterIdentityRepair = inspect(updated)
        if (options.removeUnreferencedBinaries && afterIdentityRepair.unreferencedBinaryHashes.isNotEmpty()) {
            val removals = afterIdentityRepair.unreferencedBinaryHashes
            updated = updated.modifyBinaries { pool -> pool - removals }
            removals.forEach { hash ->
                actions += KeePassMaintenanceAction(
                    type = KeePassMaintenanceActionType.REMOVE_UNREFERENCED_BINARY,
                    objectId = hash.hex(),
                    detail = "Removed an unreferenced binary"
                )
            }
        }

        val afterBinaryCleanup = inspect(updated)
        if (options.removeUnreferencedCustomIcons && afterBinaryCleanup.unreferencedCustomIconUuids.isNotEmpty()) {
            val removals = afterBinaryCleanup.unreferencedCustomIconUuids
            updated = updated.modifyCustomIcons { icons -> icons - removals }
            removals.forEach { uuid ->
                actions += KeePassMaintenanceAction(
                    type = KeePassMaintenanceActionType.REMOVE_UNREFERENCED_CUSTOM_ICON,
                    objectId = uuid.toString(),
                    detail = "Removed an unreferenced custom icon"
                )
            }
        }

        if (options.clearInvalidGroupReferences) {
            val current = inspect(updated)
            val meta = updated.content.meta
            if (current.invalidRecycleBinUuid != null) {
                updated = updated.modifyContent {
                    copy(meta = meta.copy(recycleBinEnabled = false, recycleBinUuid = null))
                }
                actions += KeePassMaintenanceAction(
                    KeePassMaintenanceActionType.CLEAR_INVALID_RECYCLE_BIN,
                    current.invalidRecycleBinUuid.toString(),
                    "Cleared an invalid recycle-bin group reference"
                )
            }
            if (current.invalidTemplateGroupUuid != null) {
                val currentMeta = updated.content.meta
                updated = updated.modifyContent {
                    copy(meta = currentMeta.copy(entryTemplatesGroup = null))
                }
                actions += KeePassMaintenanceAction(
                    KeePassMaintenanceActionType.CLEAR_INVALID_TEMPLATE_GROUP,
                    current.invalidTemplateGroupUuid.toString(),
                    "Cleared an invalid template-group reference"
                )
            }
        }

        return KeePassMaintenanceResult(
            database = updated,
            before = before,
            after = inspect(updated),
            actions = actions
        )
    }

    private fun rewriteDuplicateUuids(
        group: Group,
        usedGroupUuids: MutableSet<UUID>,
        usedEntryUuids: MutableSet<UUID>,
        actions: MutableList<KeePassMaintenanceAction>
    ): Group {
        val originalGroupUuid = group.uuid
        val groupUuid = uniqueUuid(originalGroupUuid, usedGroupUuids)
        if (groupUuid != originalGroupUuid) {
            actions += KeePassMaintenanceAction(
                KeePassMaintenanceActionType.REASSIGN_DUPLICATE_UUID,
                originalGroupUuid.toString(),
                "Reassigned duplicate group UUID to $groupUuid"
            )
        }
        val entries = group.entries.map { entry ->
            val originalEntryUuid = entry.uuid
            val entryUuid = uniqueUuid(originalEntryUuid, usedEntryUuids)
            if (entryUuid != originalEntryUuid) {
                actions += KeePassMaintenanceAction(
                    KeePassMaintenanceActionType.REASSIGN_DUPLICATE_UUID,
                    originalEntryUuid.toString(),
                    "Reassigned duplicate entry UUID to $entryUuid"
                )
            }
            entry.copy(
                uuid = entryUuid,
                history = entry.history.map { history -> history.copy(uuid = entryUuid) }
            )
        }
        return group.copy(
            uuid = groupUuid,
            groups = group.groups.map { child ->
                rewriteDuplicateUuids(child, usedGroupUuids, usedEntryUuids, actions)
            },
            entries = entries
        )
    }

    private fun uniqueUuid(preferred: UUID, used: MutableSet<UUID>): UUID {
        if (used.add(preferred)) return preferred
        var replacement: UUID
        do replacement = UUID.randomUUID() while (!used.add(replacement))
        return replacement
    }

    private fun collectEntryResources(
        entry: Entry,
        binaryReferences: MutableSet<ByteString>,
        customIconReferences: MutableSet<UUID>
    ) {
        entry.binaries.forEach { binaryReferences += it.hash }
        entry.customIconUuid?.let(customIconReferences::add)
        entry.history.forEach { history ->
            collectEntryResources(history, binaryReferences, customIconReferences)
        }
    }

    private fun <T> duplicateCounts(values: List<T>): Map<T, Int> = values
        .groupingBy { it }
        .eachCount()
        .filterValues { count -> count > 1 }

    private fun collectGroups(group: Group): List<Group> = listOf(group) + group.groups.flatMap(::collectGroups)

    private fun collectEntries(group: Group): List<Entry> = group.entries + group.groups.flatMap(::collectEntries)
}
