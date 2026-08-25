package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.util.UUID

internal data class KeePassEntryGroupRelocationResult(
    val rootGroup: Group,
    val found: Boolean,
    val moved: Boolean
)

/**
 * Updates an entry while preserving its complete KDBX payload and, when needed,
 * relocates it to another group in the same database.
 */
internal object KeePassEntryGroupRelocator {
    fun updateOrMove(
        rootGroup: Group,
        entryUuid: UUID,
        targetGroupSegments: List<String>,
        update: (Entry) -> Entry
    ): KeePassEntryGroupRelocationResult {
        val source = findEntry(rootGroup, entryUuid)
            ?: return KeePassEntryGroupRelocationResult(
                rootGroup = rootGroup,
                found = false,
                moved = false
            )
        val targetGroupUuid = findGroup(rootGroup, targetGroupSegments)?.uuid
        val updatedEntry = update(source.entry)

        if (targetGroupUuid == source.parentGroupUuid) {
            return KeePassEntryGroupRelocationResult(
                rootGroup = replaceEntry(rootGroup, entryUuid, updatedEntry),
                found = true,
                moved = false
            )
        }

        val removal = removeEntry(rootGroup, entryUuid)
        val removedEntry = removal.entry
            ?: return KeePassEntryGroupRelocationResult(
                rootGroup = rootGroup,
                found = false,
                moved = false
            )
        return KeePassEntryGroupRelocationResult(
            rootGroup = addEntry(
                group = removal.group,
                remainingSegments = targetGroupSegments,
                entry = updatedEntry
            ),
            found = true,
            moved = true
        )
    }

    private data class LocatedEntry(
        val entry: Entry,
        val parentGroupUuid: UUID
    )

    private data class EntryRemoval(
        val group: Group,
        val entry: Entry?
    )

    private fun findEntry(group: Group, entryUuid: UUID): LocatedEntry? {
        group.entries.firstOrNull { it.uuid == entryUuid }?.let { entry ->
            return LocatedEntry(entry = entry, parentGroupUuid = group.uuid)
        }
        group.groups.forEach { child ->
            findEntry(child, entryUuid)?.let { return it }
        }
        return null
    }

    private fun findGroup(group: Group, remainingSegments: List<String>): Group? {
        if (remainingSegments.isEmpty()) return group
        val child = group.groups.firstOrNull { it.name == remainingSegments.first() }
            ?: return null
        return findGroup(child, remainingSegments.drop(1))
    }

    private fun replaceEntry(group: Group, entryUuid: UUID, replacement: Entry): Group {
        return group.copy(
            entries = group.entries.map { entry ->
                if (entry.uuid == entryUuid) replacement else entry
            },
            groups = group.groups.map { child ->
                replaceEntry(child, entryUuid, replacement)
            }
        )
    }

    private fun removeEntry(group: Group, entryUuid: UUID): EntryRemoval {
        val localIndex = group.entries.indexOfFirst { it.uuid == entryUuid }
        if (localIndex >= 0) {
            val entries = group.entries.toMutableList()
            val removed = entries.removeAt(localIndex)
            return EntryRemoval(group = group.copy(entries = entries), entry = removed)
        }

        group.groups.forEachIndexed { index, child ->
            val childRemoval = removeEntry(child, entryUuid)
            if (childRemoval.entry != null) {
                val groups = group.groups.toMutableList()
                groups[index] = childRemoval.group
                return EntryRemoval(group = group.copy(groups = groups), entry = childRemoval.entry)
            }
        }
        return EntryRemoval(group = group, entry = null)
    }

    private fun addEntry(
        group: Group,
        remainingSegments: List<String>,
        entry: Entry
    ): Group {
        if (remainingSegments.isEmpty()) {
            return group.copy(entries = group.entries + entry)
        }

        val childName = remainingSegments.first()
        val childIndex = group.groups.indexOfFirst { it.name == childName }
        val child = if (childIndex >= 0) {
            group.groups[childIndex]
        } else {
            Group(uuid = UUID.randomUUID(), name = childName)
        }
        val updatedChild = addEntry(
            group = child,
            remainingSegments = remainingSegments.drop(1),
            entry = entry
        )
        val groups = group.groups.toMutableList()
        if (childIndex >= 0) {
            groups[childIndex] = updatedChild
        } else {
            groups += updatedChild
        }
        return group.copy(groups = groups)
    }
}
