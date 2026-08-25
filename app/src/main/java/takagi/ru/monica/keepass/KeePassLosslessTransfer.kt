package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.database.modifiers.removeUnusedBinaries
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import okio.ByteString
import java.util.UUID

internal data class KeePassNativeEntryTransferPayload(
    val entry: Entry,
    val sourceParentGroupUuid: UUID,
    val binaryPool: Map<ByteString, BinaryData>,
    val customIcons: Map<UUID, CustomIcon>
)

/**
 * Enforces the durable ordering required by cross-database moves.
 *
 * Source removal is intentionally unreachable until the target write and its
 * read-back verification have both completed successfully.
 */
internal object KeePassTargetFirstTransfer {
    suspend fun <T> execute(
        persistTarget: suspend () -> T,
        verifyTarget: suspend (T) -> Unit,
        removeSource: suspend () -> Unit
    ): T {
        val targetResult = persistTarget()
        verifyTarget(targetResult)
        removeSource()
        return targetResult
    }
}

/**
 * Transfers complete native KDBX entries without passing through Monica Room models.
 *
 * Capture, target insertion, and source removal are deliberately separate. Durable
 * callers can persist and verify the target before invoking [removeEntry].
 */
internal object KeePassLosslessTransfer {
    fun captureEntry(
        sourceDatabase: KeePassDatabase,
        sourceEntryUuid: UUID,
        targetEntryUuid: UUID = sourceEntryUuid
    ): KeePassNativeEntryTransferPayload {
        val located = findEntry(sourceDatabase.content.group, sourceEntryUuid)
            ?: throw IllegalArgumentException("KeePass entry not found: $sourceEntryUuid")
        val transferredEntry = if (targetEntryUuid == sourceEntryUuid) {
            located.entry
        } else {
            located.entry.copy(uuid = targetEntryUuid)
        }
        val binaryHashes = linkedSetOf<ByteString>()
        val customIconUuids = linkedSetOf<UUID>()
        collectResources(transferredEntry, binaryHashes, customIconUuids)

        val sourceBinaries = sourceDatabase.binaries
        val binaryPool = binaryHashes.associateWith { hash ->
            sourceBinaries[hash]
                ?: throw IllegalStateException("KeePass entry references a missing binary: $hash")
        }
        val sourceCustomIcons = sourceDatabase.content.meta.customIcons
        val customIcons = customIconUuids.associateWith { uuid ->
            sourceCustomIcons[uuid]
                ?: throw IllegalStateException("KeePass entry references a missing custom icon: $uuid")
        }
        return KeePassNativeEntryTransferPayload(
            entry = transferredEntry,
            sourceParentGroupUuid = located.parentGroupUuid,
            binaryPool = binaryPool,
            customIcons = customIcons
        )
    }

    fun insertEntry(
        targetDatabase: KeePassDatabase,
        targetGroupUuid: UUID,
        payload: KeePassNativeEntryTransferPayload
    ): KeePassDatabase {
        if (findGroup(targetDatabase.content.group, targetGroupUuid) == null) {
            throw IllegalArgumentException("KeePass target group not found: $targetGroupUuid")
        }
        findEntry(targetDatabase.content.group, payload.entry.uuid)?.let { existing ->
            val isEquivalentRetry =
                existing.parentGroupUuid == targetGroupUuid &&
                    entriesEquivalent(existing.entry, payload.entry)
            if (!isEquivalentRetry) {
                throw IllegalStateException("KeePass target entry UUID already exists: ${payload.entry.uuid}")
            }
            return mergeResources(targetDatabase, payload)
        }

        return mergeResources(targetDatabase, payload).modifyParentGroup {
            insertEntryIntoGroup(this, targetGroupUuid, payload.entry).group
        }
    }

    fun relocateEntry(
        database: KeePassDatabase,
        sourceEntryUuid: UUID,
        targetGroupUuid: UUID
    ): KeePassDatabase {
        val payload = captureEntry(database, sourceEntryUuid)
        if (payload.sourceParentGroupUuid == targetGroupUuid) return database
        if (findGroup(database.content.group, targetGroupUuid) == null) {
            throw IllegalArgumentException("KeePass target group not found: $targetGroupUuid")
        }
        val removal = removeEntryFromGroup(database.content.group, sourceEntryUuid)
        if (removal.entry == null) {
            throw IllegalArgumentException("KeePass entry not found: $sourceEntryUuid")
        }
        val detached = database.modifyParentGroup { removal.group }
        return insertEntry(detached, targetGroupUuid, payload)
    }

    fun removeEntry(
        sourceDatabase: KeePassDatabase,
        sourceEntryUuid: UUID
    ): KeePassDatabase {
        val removal = removeEntryFromGroup(sourceDatabase.content.group, sourceEntryUuid)
        val removedEntry = removal.entry
            ?: throw IllegalArgumentException("KeePass entry not found: $sourceEntryUuid")
        val withTombstone = sourceDatabase.modifyContent {
            KeePassNativeMutation().recordPermanentDeletion(
                content = copy(group = removal.group),
                entry = removedEntry
            )
        }
        return withTombstone.removeUnusedBinaries()
    }

    private fun mergeResources(
        targetDatabase: KeePassDatabase,
        payload: KeePassNativeEntryTransferPayload
    ): KeePassDatabase {
        val withBinaries = targetDatabase.modifyBinaries { existing ->
            buildMap {
                putAll(existing)
                payload.binaryPool.forEach { (hash, binary) ->
                    val current = existing[hash]
                    if (current != null && !current.rawContent.contentEquals(binary.rawContent)) {
                        throw IllegalStateException("KeePass binary hash collision: $hash")
                    }
                    if (current == null) put(hash, binary)
                }
            }
        }
        return withBinaries.modifyCustomIcons { existing ->
            buildMap {
                putAll(existing)
                payload.customIcons.forEach { (uuid, icon) ->
                    val current = existing[uuid]
                    if (current != null && !customIconsEquivalent(current, icon)) {
                        throw IllegalStateException("KeePass custom icon UUID collision: $uuid")
                    }
                    if (current == null) put(uuid, icon)
                }
            }
        }
    }

    private data class LocatedEntry(
        val entry: Entry,
        val parentGroupUuid: UUID
    )

    private data class EntryRemoval(
        val group: Group,
        val entry: Entry?
    )

    private data class EntryInsertion(
        val group: Group,
        val inserted: Boolean
    )

    private fun findEntry(group: Group, entryUuid: UUID): LocatedEntry? {
        group.entries.firstOrNull { it.uuid == entryUuid }?.let { entry ->
            return LocatedEntry(entry, group.uuid)
        }
        group.groups.forEach { child ->
            findEntry(child, entryUuid)?.let { return it }
        }
        return null
    }

    private fun findGroup(group: Group, groupUuid: UUID): Group? {
        if (group.uuid == groupUuid) return group
        group.groups.forEach { child ->
            findGroup(child, groupUuid)?.let { return it }
        }
        return null
    }

    private fun insertEntryIntoGroup(
        group: Group,
        targetGroupUuid: UUID,
        entry: Entry
    ): EntryInsertion {
        if (group.uuid == targetGroupUuid) {
            return EntryInsertion(group.copy(entries = group.entries + entry), true)
        }
        group.groups.forEachIndexed { index, child ->
            val insertion = insertEntryIntoGroup(child, targetGroupUuid, entry)
            if (insertion.inserted) {
                val groups = group.groups.toMutableList()
                groups[index] = insertion.group
                return EntryInsertion(group.copy(groups = groups), true)
            }
        }
        return EntryInsertion(group, false)
    }

    private fun removeEntryFromGroup(group: Group, entryUuid: UUID): EntryRemoval {
        val localIndex = group.entries.indexOfFirst { it.uuid == entryUuid }
        if (localIndex >= 0) {
            val entries = group.entries.toMutableList()
            val removed = entries.removeAt(localIndex)
            return EntryRemoval(group.copy(entries = entries), removed)
        }
        group.groups.forEachIndexed { index, child ->
            val childRemoval = removeEntryFromGroup(child, entryUuid)
            if (childRemoval.entry != null) {
                val groups = group.groups.toMutableList()
                groups[index] = childRemoval.group
                return EntryRemoval(group.copy(groups = groups), childRemoval.entry)
            }
        }
        return EntryRemoval(group, null)
    }

    private fun collectResources(
        entry: Entry,
        binaryHashes: MutableSet<ByteString>,
        customIconUuids: MutableSet<UUID>
    ) {
        entry.binaries.forEach { binaryHashes += it.hash }
        entry.customIconUuid?.let(customIconUuids::add)
        entry.history.forEach { history ->
            collectResources(history, binaryHashes, customIconUuids)
        }
    }

    fun entriesEquivalent(expected: Entry, actual: Entry): Boolean {
        return expected.uuid == actual.uuid &&
            expected.icon == actual.icon &&
            expected.customIconUuid == actual.customIconUuid &&
            expected.foregroundColor == actual.foregroundColor &&
            expected.backgroundColor == actual.backgroundColor &&
            expected.overrideUrl == actual.overrideUrl &&
            expected.times == actual.times &&
            expected.autoType == actual.autoType &&
            fieldsEquivalent(expected, actual) &&
            expected.tags == actual.tags &&
            expected.binaries == actual.binaries &&
            expected.history.size == actual.history.size &&
            expected.history.zip(actual.history).all { (left, right) ->
                entriesEquivalent(left, right)
            } &&
            expected.customData == actual.customData &&
            expected.previousParentGroup == actual.previousParentGroup &&
            expected.qualityCheck == actual.qualityCheck
    }

    private fun fieldsEquivalent(expected: Entry, actual: Entry): Boolean {
        val expectedFields = expected.fields.map { (name, value) ->
            Triple(name, value is EntryValue.Encrypted, value.content)
        }.sortedBy { it.first }
        val actualFields = actual.fields.map { (name, value) ->
            Triple(name, value is EntryValue.Encrypted, value.content)
        }.sortedBy { it.first }
        return expectedFields == actualFields
    }

    private fun customIconsEquivalent(expected: CustomIcon, actual: CustomIcon): Boolean {
        return expected.data.contentEquals(actual.data) &&
            expected.name == actual.name &&
            expected.lastModified == actual.lastModified
    }
}
