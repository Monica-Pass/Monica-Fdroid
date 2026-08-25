package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.util.UUID

/** Imports a complete native KDBX tree without converting it into Monica models. */
internal object KeePassNativeDatabaseMerge {
    fun mergeFrom(
        targetDatabase: KeePassDatabase,
        sourceDatabase: KeePassDatabase,
        targetGroupUuid: UUID
    ): KeePassDatabase {
        require(findGroup(targetDatabase.content.group, targetGroupUuid) != null) {
            "KeePass merge target group not found: $targetGroupUuid"
        }

        val occupiedGroupUuids = collectGroups(targetDatabase.content.group).mapTo(mutableSetOf()) { it.uuid }
        val occupiedEntryUuids = collectEntries(targetDatabase.content.group).mapTo(mutableSetOf()) { it.uuid }
        val groupUuidMap = mutableMapOf<UUID, UUID>()
        val entryUuidMap = mutableMapOf<UUID, UUID>()
        collectGroups(sourceDatabase.content.group).forEach { group ->
            groupUuidMap[group.uuid] = reserveUuid(group.uuid, occupiedGroupUuids)
        }
        collectEntries(sourceDatabase.content.group).forEach { entry ->
            entryUuidMap[entry.uuid] = reserveUuid(entry.uuid, occupiedEntryUuids)
        }

        val iconUuidMap = buildIconUuidMap(targetDatabase, sourceDatabase)
        val clonedRootEntries = sourceDatabase.content.group.entries.map { entry ->
            cloneEntry(entry, entryUuidMap, groupUuidMap, iconUuidMap)
        }
        val clonedGroups = sourceDatabase.content.group.groups.map { group ->
            cloneGroup(group, groupUuidMap, entryUuidMap, iconUuidMap)
        }
        var mergedRoot = insertContent(
            root = targetDatabase.content.group,
            targetGroupUuid = targetGroupUuid,
            entries = clonedRootEntries,
            groups = clonedGroups
        )
        val mergedDeletedObjects = mergeDeletedObjects(
            targetDatabase.content.deletedObjects,
            sourceDatabase.content.deletedObjects,
            groupUuidMap,
            entryUuidMap
        )
        val mergedIcons = mergeCustomIcons(targetDatabase, sourceDatabase, iconUuidMap)
        val withContent = targetDatabase.modifyContent {
            copy(
                group = mergedRoot,
                deletedObjects = mergedDeletedObjects,
                meta = meta.copy(customIcons = mergedIcons)
            )
        }.modifyCustomIcons { mergedIcons }
        return withContent.modifyBinaries { targetPool ->
            buildMap {
                putAll(targetPool)
                sourceDatabase.binaries.forEach { (hash, binary) ->
                    val existing = targetPool[hash]
                    if (existing != null &&
                        (existing.memoryProtection != binary.memoryProtection ||
                            !existing.rawContent.contentEquals(binary.rawContent))
                    ) {
                        throw IllegalStateException("KeePass binary hash collision: $hash")
                    }
                    put(hash, existing ?: binary)
                }
            }
        }
    }

    private fun reserveUuid(preferred: UUID, occupied: MutableSet<UUID>): UUID {
        if (occupied.add(preferred)) return preferred
        var replacement: UUID
        do replacement = UUID.randomUUID() while (!occupied.add(replacement))
        return replacement
    }

    private fun buildIconUuidMap(
        target: KeePassDatabase,
        source: KeePassDatabase
    ): Map<UUID, UUID> {
        val occupied = target.content.meta.customIcons.keys.toMutableSet()
        return source.content.meta.customIcons.mapValues { (uuid, icon) ->
            val current = target.content.meta.customIcons[uuid]
            if (current == null || customIconsEquivalent(current, icon)) uuid else reserveUuid(uuid, occupied)
        }
    }

    private fun mergeCustomIcons(
        target: KeePassDatabase,
        source: KeePassDatabase,
        iconUuidMap: Map<UUID, UUID>
    ): Map<UUID, CustomIcon> = buildMap {
        putAll(target.content.meta.customIcons)
        source.content.meta.customIcons.forEach { (uuid, icon) -> put(iconUuidMap.getValue(uuid), icon) }
    }

    private fun cloneGroup(
        group: Group,
        groupUuidMap: Map<UUID, UUID>,
        entryUuidMap: Map<UUID, UUID>,
        iconUuidMap: Map<UUID, UUID>
    ): Group = group.copy(
        uuid = groupUuidMap.getValue(group.uuid),
        customIconUuid = group.customIconUuid?.let { iconUuidMap[it] ?: it },
        previousParentGroup = group.previousParentGroup?.let { groupUuidMap[it] ?: it },
        groups = group.groups.map { child -> cloneGroup(child, groupUuidMap, entryUuidMap, iconUuidMap) },
        entries = group.entries.map { entry -> cloneEntry(entry, entryUuidMap, groupUuidMap, iconUuidMap) }
    )

    private fun cloneEntry(
        entry: Entry,
        entryUuidMap: Map<UUID, UUID>,
        groupUuidMap: Map<UUID, UUID>,
        iconUuidMap: Map<UUID, UUID>
    ): Entry {
        val newUuid = entryUuidMap[entry.uuid] ?: entry.uuid
        return entry.copy(
            uuid = newUuid,
            customIconUuid = entry.customIconUuid?.let { iconUuidMap[it] ?: it },
            previousParentGroup = entry.previousParentGroup?.let { groupUuidMap[it] ?: it },
            history = entry.history.map { history ->
                cloneEntry(history, entryUuidMap + (history.uuid to newUuid), groupUuidMap, iconUuidMap)
            }
        )
    }

    private fun insertContent(
        root: Group,
        targetGroupUuid: UUID,
        entries: List<Entry>,
        groups: List<Group>
    ): Group {
        if (root.uuid == targetGroupUuid) {
            return root.copy(entries = root.entries + entries, groups = root.groups + groups)
        }
        return root.copy(
            groups = root.groups.map { child ->
                insertContent(child, targetGroupUuid, entries, groups)
            }
        )
    }

    private fun mergeDeletedObjects(
        target: List<DeletedObject>,
        source: List<DeletedObject>,
        groupUuidMap: Map<UUID, UUID>,
        entryUuidMap: Map<UUID, UUID>
    ): List<DeletedObject> {
        val merged = target.associateBy(DeletedObject::id).toMutableMap()
        source.forEach { deleted ->
            val remappedId = groupUuidMap[deleted.id] ?: entryUuidMap[deleted.id] ?: deleted.id
            val remapped = DeletedObject(remappedId, deleted.deletionTime)
            val existing = merged[remappedId]
            if (existing == null || existing.deletionTime < remapped.deletionTime) merged[remappedId] = remapped
        }
        return merged.values.sortedBy(DeletedObject::deletionTime)
    }

    private fun collectGroups(root: Group): List<Group> = listOf(root) + root.groups.flatMap(::collectGroups)

    private fun collectEntries(root: Group): List<Entry> = root.entries + root.groups.flatMap(::collectEntries)

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child -> findGroup(child, uuid)?.let { return it } }
        return null
    }

    private fun customIconsEquivalent(left: CustomIcon, right: CustomIcon): Boolean =
        left.name == right.name &&
            left.lastModified == right.lastModified &&
            left.data.contentEquals(right.data)
}
