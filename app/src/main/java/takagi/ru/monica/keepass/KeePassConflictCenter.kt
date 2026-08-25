package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import okio.ByteString

internal enum class KeePassConflictObjectType {
    ENTRY,
    GROUP,
    DATABASE_METADATA,
    DELETED_OBJECT,
    BINARY,
    CUSTOM_ICON
}

internal enum class KeePassConflictChangeType {
    ADDED,
    MODIFIED,
    MOVED,
    DELETED
}

internal enum class KeePassConflictDecision {
    MERGE,
    KEEP_LOCAL,
    USE_REMOTE,
    CANCEL
}

internal enum class KeePassConflictDetailKind {
    FIELD,
    LOCATION,
    EXISTENCE,
    PROPERTIES
}

internal enum class KeePassConflictResolutionSide {
    LOCAL,
    REMOTE
}

internal data class KeePassConflictDetail(
    val id: String,
    val kind: KeePassConflictDetailKind,
    val label: String,
    val localSummary: String?,
    val remoteSummary: String?,
    val protectedValue: Boolean = false
)

internal data class KeePassConflictItem(
    val id: String,
    val objectType: KeePassConflictObjectType,
    val label: String,
    val localChange: KeePassConflictChangeType?,
    val remoteChange: KeePassConflictChangeType?,
    val ambiguous: Boolean,
    val localSummary: String?,
    val remoteSummary: String?,
    val details: List<KeePassConflictDetail> = emptyList()
)

internal data class KeePassConflictSnapshot(
    val items: List<KeePassConflictItem>,
    val localChangeCount: Int,
    val remoteChangeCount: Int,
    val ambiguousCount: Int,
    val mergeRecommended: Boolean
)

internal data class KeePassConflictDecisionResult(
    val database: KeePassDatabase?,
    val snapshot: KeePassConflictSnapshot,
    val decision: KeePassConflictDecision,
    val conflictCopyCount: Int,
    val cancelled: Boolean
)

internal data class KeePassRemoteConflictPreview(
    val snapshot: KeePassConflictSnapshot,
    val localRevision: KeePassSourceRevision,
    val remoteRevision: KeePassSourceRevision,
    val baseRevision: KeePassSourceRevision,
    val remoteVersionToken: String?,
    val remoteSizeBytes: Long
)

internal data class KeePassRemoteConflictResolution(
    val decision: KeePassConflictDecision,
    val conflictCopyCount: Int,
    val finalRevision: KeePassSourceRevision?,
    val cancelled: Boolean,
    val retainedRecoveryCopies: Int
)

/**
 * Builds a complete, displayable three-way KDBX difference and applies an
 * explicit whole-database decision. Entry conflicts remain lossless during a
 * merge by keeping the local entry at its UUID and adding the remote version
 * as a marked conflict copy.
 */
internal object KeePassConflictCenter {
    fun inspect(
        baseDatabase: KeePassDatabase,
        localDatabase: KeePassDatabase,
        remoteDatabase: KeePassDatabase
    ): KeePassConflictSnapshot {
        val items = buildList {
            addAll(compareEntries(baseDatabase, localDatabase, remoteDatabase))
            addAll(compareGroups(baseDatabase, localDatabase, remoteDatabase))
            compareMetadata(baseDatabase, localDatabase, remoteDatabase)?.let(::add)
            addAll(compareDeletedObjects(baseDatabase, localDatabase, remoteDatabase))
            addAll(compareBinaries(baseDatabase, localDatabase, remoteDatabase))
            addAll(compareCustomIcons(baseDatabase, localDatabase, remoteDatabase))
        }.sortedWith(compareBy<KeePassConflictItem> { it.objectType.ordinal }.thenBy { it.label.lowercase() })
        val ambiguousCount = items.count(KeePassConflictItem::ambiguous)
        return KeePassConflictSnapshot(
            items = items,
            localChangeCount = items.count { it.localChange != null },
            remoteChangeCount = items.count { it.remoteChange != null },
            ambiguousCount = ambiguousCount,
            mergeRecommended = ambiguousCount == 0
        )
    }

    fun resolve(
        baseDatabase: KeePassDatabase,
        localDatabase: KeePassDatabase,
        remoteDatabase: KeePassDatabase,
        decision: KeePassConflictDecision
    ): KeePassConflictDecisionResult {
        val snapshot = inspect(baseDatabase, localDatabase, remoteDatabase)
        return when (decision) {
            KeePassConflictDecision.KEEP_LOCAL -> KeePassConflictDecisionResult(
                database = localDatabase,
                snapshot = snapshot,
                decision = decision,
                conflictCopyCount = 0,
                cancelled = false
            )
            KeePassConflictDecision.USE_REMOTE -> KeePassConflictDecisionResult(
                database = remoteDatabase,
                snapshot = snapshot,
                decision = decision,
                conflictCopyCount = 0,
                cancelled = false
            )
            KeePassConflictDecision.CANCEL -> KeePassConflictDecisionResult(
                database = null,
                snapshot = snapshot,
                decision = decision,
                conflictCopyCount = 0,
                cancelled = true
            )
            KeePassConflictDecision.MERGE -> {
                val merged = merge(baseDatabase, localDatabase, remoteDatabase)
                KeePassConflictDecisionResult(
                    database = merged.database,
                    snapshot = snapshot,
                    decision = decision,
                    conflictCopyCount = merged.conflictCopyCount,
                    cancelled = false
                )
            }
        }
    }

    fun resolveSelected(
        baseDatabase: KeePassDatabase,
        localDatabase: KeePassDatabase,
        remoteDatabase: KeePassDatabase,
        selections: Map<String, KeePassConflictResolutionSide>
    ): KeePassConflictDecisionResult {
        val snapshot = inspect(baseDatabase, localDatabase, remoteDatabase)
        val requiredSelectionIds = snapshot.items
            .asSequence()
            .flatMap { item -> item.details.asSequence() }
            .map(KeePassConflictDetail::id)
            .toSet()
        val missingSelectionIds = requiredSelectionIds - selections.keys
        require(missingSelectionIds.isEmpty()) {
            "Missing KeePass conflict choices: ${missingSelectionIds.sorted().joinToString()}"
        }
        val merged = merge(
            baseDatabase = baseDatabase,
            localDatabase = localDatabase,
            remoteDatabase = remoteDatabase,
            entrySelections = selections
        )
        return KeePassConflictDecisionResult(
            database = merged.database,
            snapshot = snapshot,
            decision = KeePassConflictDecision.MERGE,
            conflictCopyCount = merged.conflictCopyCount,
            cancelled = false
        )
    }

    private data class MergeResult(
        val database: KeePassDatabase,
        val conflictCopyCount: Int
    )

    private data class LocatedEntry(
        val entry: Entry,
        val parentUuid: UUID,
        val occurrence: Int
    )

    private data class LocatedGroup(
        val group: Group,
        val parentUuid: UUID?,
        val occurrence: Int
    )

    private data class ObjectState(
        val contentSignature: String,
        val parentId: String?,
        val summary: String
    )

    private data class EntryFieldState(
        val content: String,
        val protected: Boolean
    )

    private fun merge(
        baseDatabase: KeePassDatabase,
        localDatabase: KeePassDatabase,
        remoteDatabase: KeePassDatabase,
        entrySelections: Map<String, KeePassConflictResolutionSide>? = null
    ): MergeResult {
        val selectedShell = selectDatabaseShell(baseDatabase, localDatabase, remoteDatabase)
        val mergedMeta = mergeMeta(
            baseDatabase.content.meta,
            localDatabase.content.meta,
            remoteDatabase.content.meta
        )
        var mergedRoot = remoteDatabase.content.group
        var conflictCopyCount = 0

        val baseGroups = locateGroups(baseDatabase.content.group).associateBy { it.group.uuid }
        val localGroups = locateGroups(localDatabase.content.group).associateBy { it.group.uuid }
        val remoteGroups = locateGroups(remoteDatabase.content.group).associateBy { it.group.uuid }
        val handledGroupTrees = mutableSetOf<UUID>()
        val groupUuids = linkedSetOf<UUID>().apply {
            addAll(baseGroups.keys)
            addAll(localGroups.keys)
            addAll(remoteGroups.keys)
        }
        groupUuids.forEach { uuid ->
            if (uuid == remoteDatabase.content.group.uuid || isDescendantOfHandledTree(uuid, localGroups, handledGroupTrees)) {
                return@forEach
            }
            val base = baseGroups[uuid]
            val local = localGroups[uuid]
            val remote = remoteGroups[uuid]
            val localChange = changeOf(base?.groupState(), local?.groupState())
            if (localChange == null) return@forEach
            val remoteChange = changeOf(base?.groupState(), remote?.groupState())
            val divergent = remoteChange != null && local?.groupState() != remote?.groupState()

            when {
                !divergent && local == null -> {
                    mergedRoot = removeGroup(mergedRoot, uuid).group
                }
                !divergent && local != null -> {
                    val normalized = stripEntries(local.group)
                    mergedRoot = upsertGroup(
                        root = mergedRoot,
                        group = normalized,
                        parentUuid = local.parentUuid ?: mergedRoot.uuid
                    )
                }
                divergent && local != null -> {
                    val clone = cloneConflictGroup(local.group)
                    mergedRoot = insertGroup(
                        root = mergedRoot,
                        parentUuid = local.parentUuid?.takeIf { findGroup(mergedRoot, it) != null } ?: mergedRoot.uuid,
                        group = clone
                    )
                    handledGroupTrees += uuid
                    conflictCopyCount++
                }
            }
        }

        val baseEntries = locateEntries(baseDatabase.content.group).associateBy { it.entry.uuid }
        val localEntries = locateEntries(localDatabase.content.group).associateBy { it.entry.uuid }
        val remoteEntries = locateEntries(remoteDatabase.content.group).associateBy { it.entry.uuid }
        val entryUuids = linkedSetOf<UUID>().apply {
            addAll(baseEntries.keys)
            addAll(localEntries.keys)
            addAll(remoteEntries.keys)
        }
        entryUuids.forEach { uuid ->
            val base = baseEntries[uuid]
            val local = localEntries[uuid]
            val remote = remoteEntries[uuid]
            val localChange = changeOf(base?.entryState(), local?.entryState())
            if (localChange == null) return@forEach

            if (entrySelections != null) {
                val resolvedEntry = mergeSelectedEntry(
                    uuid = uuid,
                    base = base,
                    local = local,
                    remote = remote,
                    selections = entrySelections
                )
                mergedRoot = removeEntry(mergedRoot, uuid).group
                if (resolvedEntry != null) {
                    mergedRoot = insertEntry(
                        root = mergedRoot,
                        parentUuid = resolvedEntry.parentUuid
                            .takeIf { findGroup(mergedRoot, it) != null }
                            ?: mergedRoot.uuid,
                        entry = resolvedEntry.entry
                    )
                }
                return@forEach
            }

            val remoteChange = changeOf(base?.entryState(), remote?.entryState())
            val divergent = remoteChange != null && local?.entryState() != remote?.entryState()

            when {
                !divergent && local == null -> mergedRoot = removeEntry(mergedRoot, uuid).group
                !divergent && local != null -> {
                    mergedRoot = removeEntry(mergedRoot, uuid).group
                    mergedRoot = insertEntry(
                        root = mergedRoot,
                        parentUuid = local.parentUuid.takeIf { findGroup(mergedRoot, it) != null } ?: mergedRoot.uuid,
                        entry = local.entry
                    )
                }
                divergent && local != null && remote != null -> {
                    mergedRoot = removeEntry(mergedRoot, uuid).group
                    val targetParent = local.parentUuid.takeIf { findGroup(mergedRoot, it) != null } ?: mergedRoot.uuid
                    mergedRoot = insertEntry(mergedRoot, targetParent, local.entry)
                    mergedRoot = insertEntry(
                        mergedRoot,
                        remote.parentUuid.takeIf { findGroup(mergedRoot, it) != null } ?: mergedRoot.uuid,
                        buildRemoteConflictCopy(remote.entry)
                    )
                    conflictCopyCount++
                }
                divergent && local != null -> {
                    mergedRoot = removeEntry(mergedRoot, uuid).group
                    mergedRoot = insertEntry(
                        mergedRoot,
                        local.parentUuid.takeIf { findGroup(mergedRoot, it) != null } ?: mergedRoot.uuid,
                        local.entry
                    )
                }
            }
        }

        val mergedDeletedObjects = mergeDeletedObjects(
            baseDatabase.content.deletedObjects,
            localDatabase.content.deletedObjects,
            remoteDatabase.content.deletedObjects
        )
        val mergedIcons = mergeMapBySignature(
            baseDatabase.content.meta.customIcons,
            localDatabase.content.meta.customIcons,
            remoteDatabase.content.meta.customIcons,
            ::customIconSignature
        )
        val mergedBinaries = mergeMapBySignature(
            baseDatabase.binaries,
            localDatabase.binaries,
            remoteDatabase.binaries,
            ::binarySignature
        )
        val withContent = selectedShell.modifyContent {
            copy(
                meta = mergedMeta.copy(customIcons = mergedIcons),
                group = mergedRoot,
                deletedObjects = mergedDeletedObjects
            )
        }.modifyCustomIcons { mergedIcons }
            .modifyBinaries { mergedBinaries }
        return MergeResult(withContent, conflictCopyCount)
    }

    private fun compareEntries(
        base: KeePassDatabase,
        local: KeePassDatabase,
        remote: KeePassDatabase
    ): List<KeePassConflictItem> {
        val baseEntries = locateEntries(base.content.group).groupBy { it.entry.uuid }
        val localEntries = locateEntries(local.content.group).groupBy { it.entry.uuid }
        val remoteEntries = locateEntries(remote.content.group).groupBy { it.entry.uuid }
        val uuids = linkedSetOf<UUID>().apply {
            addAll(baseEntries.keys)
            addAll(localEntries.keys)
            addAll(remoteEntries.keys)
        }
        return uuids.mapNotNull { uuid ->
            val baseValues = baseEntries[uuid].orEmpty()
            val localValues = localEntries[uuid].orEmpty()
            val remoteValues = remoteEntries[uuid].orEmpty()
            val baseState = baseValues.takeIf { it.isNotEmpty() }?.combinedEntryState()
            val localState = localValues.takeIf { it.isNotEmpty() }?.combinedEntryState()
            val remoteState = remoteValues.takeIf { it.isNotEmpty() }?.combinedEntryState()
            val localChange = changeOf(baseState, localState)
            val remoteChange = changeOf(baseState, remoteState)
            if (localChange == null && remoteChange == null) return@mapNotNull null

            val objectAmbiguous = localChange != null && remoteChange != null && localState != remoteState
            val details = if (
                objectAmbiguous &&
                baseValues.size <= 1 &&
                localValues.size <= 1 &&
                remoteValues.size <= 1
            ) {
                buildEntryConflictDetails(
                    uuid = uuid,
                    base = baseValues.singleOrNull(),
                    local = localValues.singleOrNull(),
                    remote = remoteValues.singleOrNull(),
                    baseDatabase = base,
                    localDatabase = local,
                    remoteDatabase = remote
                )
            } else {
                emptyList()
            }
            val ambiguous = if (
                baseValues.size <= 1 &&
                localValues.size <= 1 &&
                remoteValues.size <= 1
            ) {
                details.isNotEmpty()
            } else {
                objectAmbiguous
            }
            KeePassConflictItem(
                id = uuid.toString(),
                objectType = KeePassConflictObjectType.ENTRY,
                label = localState?.summary ?: remoteState?.summary ?: uuid.toString(),
                localChange = localChange,
                remoteChange = remoteChange,
                ambiguous = ambiguous,
                localSummary = localState?.summary,
                remoteSummary = remoteState?.summary,
                details = details
            )
        }
    }

    private fun buildEntryConflictDetails(
        uuid: UUID,
        base: LocatedEntry?,
        local: LocatedEntry?,
        remote: LocatedEntry?,
        baseDatabase: KeePassDatabase,
        localDatabase: KeePassDatabase,
        remoteDatabase: KeePassDatabase
    ): List<KeePassConflictDetail> {
        if (local == null || remote == null) {
            return listOf(
                KeePassConflictDetail(
                    id = entryDetailId(uuid, KeePassConflictDetailKind.EXISTENCE),
                    kind = KeePassConflictDetailKind.EXISTENCE,
                    label = "Entry",
                    localSummary = local?.entry?.let(::entryDisplaySummary) ?: "Deleted",
                    remoteSummary = remote?.entry?.let(::entryDisplaySummary) ?: "Deleted"
                )
            )
        }

        val details = mutableListOf<KeePassConflictDetail>()
        val fieldNames = linkedSetOf<String>().apply {
            base?.entry?.fields?.forEach { (name, _) -> add(name) }
            local.entry.fields.forEach { (name, _) -> add(name) }
            remote.entry.fields.forEach { (name, _) -> add(name) }
        }
        fieldNames.forEach { name ->
            val baseValue = base?.entry?.fields?.get(name)
            val localValue = local.entry.fields[name]
            val remoteValue = remote.entry.fields[name]
            if (isThreeWayConflict(fieldState(baseValue), fieldState(localValue), fieldState(remoteValue))) {
                val protectedValue = name.equals("Password", ignoreCase = true) ||
                    localValue is EntryValue.Encrypted ||
                    remoteValue is EntryValue.Encrypted
                details += KeePassConflictDetail(
                    id = entryDetailId(uuid, KeePassConflictDetailKind.FIELD, name),
                    kind = KeePassConflictDetailKind.FIELD,
                    label = name,
                    localSummary = fieldSummary(localValue, protectedValue),
                    remoteSummary = fieldSummary(remoteValue, protectedValue),
                    protectedValue = protectedValue
                )
            }
        }

        if (isThreeWayConflict(base?.parentUuid, local.parentUuid, remote.parentUuid)) {
            details += KeePassConflictDetail(
                id = entryDetailId(uuid, KeePassConflictDetailKind.LOCATION),
                kind = KeePassConflictDetailKind.LOCATION,
                label = "Folder",
                localSummary = groupDisplaySummary(localDatabase, local.parentUuid),
                remoteSummary = groupDisplaySummary(remoteDatabase, remote.parentUuid)
            )
        }

        if (hasEntryPropertyConflict(base?.entry, local.entry, remote.entry)) {
            details += KeePassConflictDetail(
                id = entryDetailId(uuid, KeePassConflictDetailKind.PROPERTIES),
                kind = KeePassConflictDetailKind.PROPERTIES,
                label = "KeePass properties",
                localSummary = "Local properties",
                remoteSummary = "Remote properties"
            )
        }

        return details
    }

    private fun mergeSelectedEntry(
        uuid: UUID,
        base: LocatedEntry?,
        local: LocatedEntry?,
        remote: LocatedEntry?,
        selections: Map<String, KeePassConflictResolutionSide>
    ): LocatedEntry? {
        val baseState = base?.entryState()
        val localState = local?.entryState()
        val remoteState = remote?.entryState()
        val localChanged = baseState != localState
        val remoteChanged = baseState != remoteState

        if (!localChanged) return remote
        if (!remoteChanged || localState == remoteState) return local

        if (local == null || remote == null) {
            return when (selections.getValue(entryDetailId(uuid, KeePassConflictDetailKind.EXISTENCE))) {
                KeePassConflictResolutionSide.LOCAL -> local
                KeePassConflictResolutionSide.REMOTE -> remote
            }
        }

        val propertyDetailId = entryDetailId(uuid, KeePassConflictDetailKind.PROPERTIES)
        val locationDetailId = entryDetailId(uuid, KeePassConflictDetailKind.LOCATION)
        val fieldNames = linkedSetOf<String>().apply {
            base?.entry?.fields?.forEach { (name, _) -> add(name) }
            local.entry.fields.forEach { (name, _) -> add(name) }
            remote.entry.fields.forEach { (name, _) -> add(name) }
        }
        val mergedFields = fieldNames.mapNotNull { name ->
            selectEntryValue(
                base = base?.entry?.fields?.get(name),
                local = local.entry.fields[name],
                remote = remote.entry.fields[name],
                detailId = entryDetailId(uuid, KeePassConflictDetailKind.FIELD, name),
                selections = selections
            )?.let { value -> name to value }
        }
        val mergedParentUuid = selectThreeWay(
            base = base?.parentUuid,
            local = local.parentUuid,
            remote = remote.parentUuid,
            detailId = locationDetailId,
            selections = selections
        ) ?: local.parentUuid
        val mergedEntry = remote.entry.copy(
            uuid = uuid,
            fields = EntryFields.of(*mergedFields.toTypedArray()),
            icon = selectThreeWay(
                base?.entry?.icon,
                local.entry.icon,
                remote.entry.icon,
                propertyDetailId,
                selections
            ) ?: remote.entry.icon,
            customIconUuid = selectThreeWay(
                base?.entry?.customIconUuid,
                local.entry.customIconUuid,
                remote.entry.customIconUuid,
                propertyDetailId,
                selections
            ),
            foregroundColor = selectThreeWay(
                base?.entry?.foregroundColor,
                local.entry.foregroundColor,
                remote.entry.foregroundColor,
                propertyDetailId,
                selections
            ),
            backgroundColor = selectThreeWay(
                base?.entry?.backgroundColor,
                local.entry.backgroundColor,
                remote.entry.backgroundColor,
                propertyDetailId,
                selections
            ),
            overrideUrl = selectThreeWay(
                base?.entry?.overrideUrl,
                local.entry.overrideUrl,
                remote.entry.overrideUrl,
                propertyDetailId,
                selections
            ) ?: remote.entry.overrideUrl,
            autoType = selectThreeWay(
                base?.entry?.autoType,
                local.entry.autoType,
                remote.entry.autoType,
                propertyDetailId,
                selections
            ),
            tags = selectThreeWay(
                base?.entry?.tags,
                local.entry.tags,
                remote.entry.tags,
                propertyDetailId,
                selections
            ) ?: remote.entry.tags,
            binaries = selectThreeWay(
                base?.entry?.binaries,
                local.entry.binaries,
                remote.entry.binaries,
                propertyDetailId,
                selections
            ) ?: remote.entry.binaries,
            history = mergeEntryHistory(
                base?.entry?.history.orEmpty(),
                local.entry.history,
                remote.entry.history
            ),
            times = mergeEntryTimes(
                base?.entry?.times,
                local.entry.times,
                remote.entry.times,
                propertyDetailId,
                selections
            ),
            customData = selectThreeWay(
                base?.entry?.customData,
                local.entry.customData,
                remote.entry.customData,
                propertyDetailId,
                selections
            ) ?: remote.entry.customData,
            previousParentGroup = selectThreeWay(
                base?.entry?.previousParentGroup,
                local.entry.previousParentGroup,
                remote.entry.previousParentGroup,
                propertyDetailId,
                selections
            ),
            qualityCheck = selectThreeWay(
                base?.entry?.qualityCheck,
                local.entry.qualityCheck,
                remote.entry.qualityCheck,
                propertyDetailId,
                selections
            ) ?: remote.entry.qualityCheck
        )
        return LocatedEntry(mergedEntry, mergedParentUuid, occurrence = 0)
    }

    private fun selectEntryValue(
        base: EntryValue?,
        local: EntryValue?,
        remote: EntryValue?,
        detailId: String,
        selections: Map<String, KeePassConflictResolutionSide>
    ): EntryValue? {
        val baseState = fieldState(base)
        val localState = fieldState(local)
        val remoteState = fieldState(remote)
        return when {
            localState == baseState -> remote
            remoteState == baseState -> local
            localState == remoteState -> local
            selections[detailId] == KeePassConflictResolutionSide.REMOTE -> remote
            else -> local
        }
    }

    private fun <T> selectThreeWay(
        base: T,
        local: T,
        remote: T,
        detailId: String,
        selections: Map<String, KeePassConflictResolutionSide>
    ): T = when {
        local == base -> remote
        remote == base -> local
        local == remote -> local
        selections[detailId] == KeePassConflictResolutionSide.REMOTE -> remote
        else -> local
    }

    private fun mergeEntryHistory(
        base: List<Entry>,
        local: List<Entry>,
        remote: List<Entry>
    ): List<Entry> {
        val merged = linkedMapOf<String, Entry>()
        (base + local + remote).forEach { entry ->
            val key = buildString {
                append(KeePassEntryFingerprint.build(entry))
                append('|')
                append(entry.times?.lastModificationTime)
            }
            merged.putIfAbsent(key, entry)
        }
        return merged.values.sortedBy { entry -> entry.times?.lastModificationTime ?: Instant.MIN }
    }

    private fun mergeEntryTimes(
        base: TimeData?,
        local: TimeData?,
        remote: TimeData?,
        propertyDetailId: String,
        selections: Map<String, KeePassConflictResolutionSide>
    ): TimeData? {
        val candidates = listOfNotNull(base, local, remote)
        val template = candidates.maxByOrNull { value -> value.lastModificationTime ?: Instant.MIN } ?: return null
        val expires = if (selections.containsKey(propertyDetailId)) {
            selectThreeWay(base?.expires, local?.expires, remote?.expires, propertyDetailId, selections)
                ?: template.expires
        } else {
            template.expires
        }
        val expiryTime = if (selections.containsKey(propertyDetailId)) {
            selectThreeWay(
                base?.expiryTime,
                local?.expiryTime,
                remote?.expiryTime,
                propertyDetailId,
                selections
            ) ?: template.expiryTime
        } else {
            template.expiryTime
        }
        return template.copy(
            creationTime = candidates.mapNotNull(TimeData::creationTime).minOrNull() ?: template.creationTime,
            lastAccessTime = candidates.mapNotNull(TimeData::lastAccessTime).maxOrNull() ?: template.lastAccessTime,
            lastModificationTime = candidates.mapNotNull(TimeData::lastModificationTime).maxOrNull()
                ?: template.lastModificationTime,
            expiryTime = expiryTime,
            expires = expires,
            usageCount = candidates.maxOf(TimeData::usageCount),
            locationChanged = candidates.mapNotNull(TimeData::locationChanged).maxOrNull() ?: template.locationChanged
        )
    }

    private fun fieldState(value: EntryValue?): EntryFieldState? = value?.let {
        EntryFieldState(
            content = runCatching { it.content }.getOrDefault(""),
            protected = it is EntryValue.Encrypted
        )
    }

    private fun fieldSummary(value: EntryValue?, forceMask: Boolean = false): String = when {
        value == null -> "Not present"
        forceMask || value is EntryValue.Encrypted -> "••••••"
        value.content.isEmpty() -> "Empty"
        else -> value.content.take(120)
    }

    private fun entryDisplaySummary(entry: Entry): String =
        entry.fields["Title"]?.content?.ifBlank { "Untitled entry" } ?: "Untitled entry"

    private fun groupDisplaySummary(database: KeePassDatabase, uuid: UUID): String =
        findGroup(database.content.group, uuid)?.name?.ifBlank { uuid.toString() } ?: uuid.toString()

    private fun hasEntryPropertyConflict(base: Entry?, local: Entry, remote: Entry): Boolean {
        fun conflict(baseValue: Any?, localValue: Any?, remoteValue: Any?): Boolean =
            localValue != baseValue && remoteValue != baseValue && localValue != remoteValue

        val conflicts = listOf(
            "icon" to conflict(base?.icon, local.icon, remote.icon),
            "customIconUuid" to conflict(base?.customIconUuid, local.customIconUuid, remote.customIconUuid),
            "foregroundColor" to conflict(base?.foregroundColor, local.foregroundColor, remote.foregroundColor),
            "backgroundColor" to conflict(base?.backgroundColor, local.backgroundColor, remote.backgroundColor),
            "overrideUrl" to conflict(base?.overrideUrl, local.overrideUrl, remote.overrideUrl),
            "autoType" to conflict(base?.autoType, local.autoType, remote.autoType),
            "tags" to conflict(base?.tags, local.tags, remote.tags),
            "binaries" to conflict(base?.binaries, local.binaries, remote.binaries),
            "customData" to conflict(base?.customData, local.customData, remote.customData),
            "previousParentGroup" to conflict(base?.previousParentGroup, local.previousParentGroup, remote.previousParentGroup),
            "qualityCheck" to conflict(base?.qualityCheck, local.qualityCheck, remote.qualityCheck),
            "expires" to conflict(base?.times?.expires, local.times?.expires, remote.times?.expires),
            "expiryTime" to conflict(base?.times?.expiryTime, local.times?.expiryTime, remote.times?.expiryTime)
        )
        return conflicts.any { it.second }
    }

    private fun entryDetailId(
        uuid: UUID,
        kind: KeePassConflictDetailKind,
        fieldName: String? = null
    ): String {
        val suffix = fieldName?.let { sha256(it.toByteArray()).take(16) } ?: kind.name.lowercase()
        return "entry:$uuid:${kind.name.lowercase()}:$suffix"
    }

    private fun <T> isThreeWayConflict(base: T, local: T, remote: T): Boolean =
        local != base && remote != base && local != remote

    private fun compareGroups(
        base: KeePassDatabase,
        local: KeePassDatabase,
        remote: KeePassDatabase
    ): List<KeePassConflictItem> = compareObjects(
        type = KeePassConflictObjectType.GROUP,
        base = locateGroups(base.content.group).groupBy { it.group.uuid }.mapValues { (_, values) -> values.combinedGroupState() },
        local = locateGroups(local.content.group).groupBy { it.group.uuid }.mapValues { (_, values) -> values.combinedGroupState() },
        remote = locateGroups(remote.content.group).groupBy { it.group.uuid }.mapValues { (_, values) -> values.combinedGroupState() }
    )

    private fun compareMetadata(
        base: KeePassDatabase,
        local: KeePassDatabase,
        remote: KeePassDatabase
    ): KeePassConflictItem? {
        val baseState = metadataState(base)
        val localState = metadataState(local)
        val remoteState = metadataState(remote)
        val localChange = changeOf(baseState, localState)
        val remoteChange = changeOf(baseState, remoteState)
        if (localChange == null && remoteChange == null) return null
        return KeePassConflictItem(
            id = "database-metadata",
            objectType = KeePassConflictObjectType.DATABASE_METADATA,
            label = "Database settings",
            localChange = localChange,
            remoteChange = remoteChange,
            ambiguous = localChange != null && remoteChange != null && localState != remoteState,
            localSummary = localState.summary,
            remoteSummary = remoteState.summary
        )
    }

    private fun compareDeletedObjects(
        base: KeePassDatabase,
        local: KeePassDatabase,
        remote: KeePassDatabase
    ): List<KeePassConflictItem> = compareObjects(
        type = KeePassConflictObjectType.DELETED_OBJECT,
        base = base.content.deletedObjects.associate { it.id.toString() to deletedObjectState(it) },
        local = local.content.deletedObjects.associate { it.id.toString() to deletedObjectState(it) },
        remote = remote.content.deletedObjects.associate { it.id.toString() to deletedObjectState(it) }
    )

    private fun compareBinaries(
        base: KeePassDatabase,
        local: KeePassDatabase,
        remote: KeePassDatabase
    ): List<KeePassConflictItem> = compareObjects(
        type = KeePassConflictObjectType.BINARY,
        base = base.binaries.mapKeys { it.key.hex() }.mapValues { ObjectState(binarySignature(it.value), null, "${it.value.rawContent.size} bytes") },
        local = local.binaries.mapKeys { it.key.hex() }.mapValues { ObjectState(binarySignature(it.value), null, "${it.value.rawContent.size} bytes") },
        remote = remote.binaries.mapKeys { it.key.hex() }.mapValues { ObjectState(binarySignature(it.value), null, "${it.value.rawContent.size} bytes") }
    )

    private fun compareCustomIcons(
        base: KeePassDatabase,
        local: KeePassDatabase,
        remote: KeePassDatabase
    ): List<KeePassConflictItem> = compareObjects(
        type = KeePassConflictObjectType.CUSTOM_ICON,
        base = base.content.meta.customIcons.mapKeys { it.key.toString() }.mapValues { ObjectState(customIconSignature(it.value), null, it.value.name.orEmpty()) },
        local = local.content.meta.customIcons.mapKeys { it.key.toString() }.mapValues { ObjectState(customIconSignature(it.value), null, it.value.name.orEmpty()) },
        remote = remote.content.meta.customIcons.mapKeys { it.key.toString() }.mapValues { ObjectState(customIconSignature(it.value), null, it.value.name.orEmpty()) }
    )

    private fun compareObjects(
        type: KeePassConflictObjectType,
        base: Map<*, ObjectState>,
        local: Map<*, ObjectState>,
        remote: Map<*, ObjectState>
    ): List<KeePassConflictItem> {
        val ids = linkedSetOf<Any?>().apply {
            addAll(base.keys)
            addAll(local.keys)
            addAll(remote.keys)
        }
        return ids.mapNotNull { rawId ->
            val baseState = base[rawId]
            val localState = local[rawId]
            val remoteState = remote[rawId]
            val localChange = changeOf(baseState, localState)
            val remoteChange = changeOf(baseState, remoteState)
            if (localChange == null && remoteChange == null) return@mapNotNull null
            KeePassConflictItem(
                id = rawId.toString(),
                objectType = type,
                label = localState?.summary ?: remoteState?.summary ?: rawId.toString(),
                localChange = localChange,
                remoteChange = remoteChange,
                ambiguous = localChange != null && remoteChange != null && localState != remoteState,
                localSummary = localState?.summary,
                remoteSummary = remoteState?.summary
            )
        }
    }

    private fun changeOf(base: ObjectState?, candidate: ObjectState?): KeePassConflictChangeType? = when {
        base == null && candidate == null -> null
        base == null -> KeePassConflictChangeType.ADDED
        candidate == null -> KeePassConflictChangeType.DELETED
        base == candidate -> null
        base.contentSignature == candidate.contentSignature && base.parentId != candidate.parentId -> KeePassConflictChangeType.MOVED
        else -> KeePassConflictChangeType.MODIFIED
    }

    private fun locateEntries(root: Group): List<LocatedEntry> {
        val result = mutableListOf<LocatedEntry>()
        val occurrences = mutableMapOf<UUID, Int>()
        fun visit(group: Group) {
            group.entries.forEach { entry ->
                val occurrence = occurrences.getOrDefault(entry.uuid, 0)
                occurrences[entry.uuid] = occurrence + 1
                result += LocatedEntry(entry, group.uuid, occurrence)
            }
            group.groups.forEach(::visit)
        }
        visit(root)
        return result
    }

    private fun locateGroups(root: Group): List<LocatedGroup> {
        val result = mutableListOf<LocatedGroup>()
        val occurrences = mutableMapOf<UUID, Int>()
        fun visit(group: Group, parentUuid: UUID?) {
            val occurrence = occurrences.getOrDefault(group.uuid, 0)
            occurrences[group.uuid] = occurrence + 1
            result += LocatedGroup(group, parentUuid, occurrence)
            group.groups.forEach { child -> visit(child, group.uuid) }
        }
        visit(root, null)
        return result
    }

    private fun List<LocatedEntry>.combinedEntryState(): ObjectState {
        val states = sortedBy { it.occurrence }.map { located -> located.entryState() }
        return ObjectState(
            contentSignature = states.joinToString("|") { it.contentSignature },
            parentId = states.joinToString("|") { it.parentId.orEmpty() },
            summary = states.firstOrNull()?.summary.orEmpty() + if (size > 1) " ×$size" else ""
        )
    }

    private fun List<LocatedGroup>.combinedGroupState(): ObjectState {
        val states = sortedBy { it.occurrence }.map { located -> located.groupState() }
        return ObjectState(
            contentSignature = states.joinToString("|") { it.contentSignature },
            parentId = states.joinToString("|") { it.parentId.orEmpty() },
            summary = states.firstOrNull()?.summary.orEmpty() + if (size > 1) " ×$size" else ""
        )
    }

    private fun LocatedEntry.entryState(): ObjectState = ObjectState(
        contentSignature = KeePassEntryFingerprint.build(entry),
        parentId = parentUuid.toString(),
        summary = entry.fields["Title"]?.content?.ifBlank { "Untitled entry" } ?: "Untitled entry"
    )

    private fun LocatedGroup.groupState(): ObjectState = ObjectState(
        contentSignature = sha256(
            group.copy(
                times = null,
                groups = emptyList(),
                entries = emptyList()
            ).toString().toByteArray()
        ),
        parentId = parentUuid?.toString(),
        summary = group.name.ifBlank { "Unnamed group" }
    )

    private fun metadataState(database: KeePassDatabase): ObjectState {
        val meta = database.content.meta.copy(customIcons = emptyMap())
        return ObjectState(
            contentSignature = sha256((database.header.toString() + "|" + meta.toString()).toByteArray()),
            parentId = null,
            summary = meta.name.ifBlank { "Database settings" }
        )
    }

    private fun deletedObjectState(value: DeletedObject): ObjectState = ObjectState(
        contentSignature = value.deletionTime.toString(),
        parentId = null,
        summary = value.id.toString()
    )

    private fun binarySignature(binary: BinaryData): String = sha256(
        byteArrayOf(if (binary.memoryProtection) 1 else 0) + binary.rawContent
    )

    private fun customIconSignature(icon: CustomIcon): String = sha256(
        icon.data + icon.name.orEmpty().toByteArray() + icon.lastModified.toString().toByteArray()
    )

    private fun selectDatabaseShell(
        base: KeePassDatabase,
        local: KeePassDatabase,
        remote: KeePassDatabase
    ): KeePassDatabase {
        val baseHeader = base.header.toString()
        val localHeader = local.header.toString()
        val remoteHeader = remote.header.toString()
        return when {
            localHeader == baseHeader -> remote
            remoteHeader == baseHeader -> local
            localHeader == remoteHeader -> local
            else -> local
        }
    }

    private fun mergeMeta(base: Meta, local: Meta, remote: Meta): Meta = remote.copy(
        generator = selectValue(base.generator, local.generator, remote.generator),
        headerHash = selectValue(base.headerHash, local.headerHash, remote.headerHash),
        settingsChanged = selectValue(base.settingsChanged, local.settingsChanged, remote.settingsChanged),
        name = selectValue(base.name, local.name, remote.name),
        nameChanged = selectValue(base.nameChanged, local.nameChanged, remote.nameChanged),
        description = selectValue(base.description, local.description, remote.description),
        descriptionChanged = selectValue(base.descriptionChanged, local.descriptionChanged, remote.descriptionChanged),
        defaultUser = selectValue(base.defaultUser, local.defaultUser, remote.defaultUser),
        defaultUserChanged = selectValue(base.defaultUserChanged, local.defaultUserChanged, remote.defaultUserChanged),
        maintenanceHistoryDays = selectValue(base.maintenanceHistoryDays, local.maintenanceHistoryDays, remote.maintenanceHistoryDays),
        color = selectValue(base.color, local.color, remote.color),
        masterKeyChanged = selectValue(base.masterKeyChanged, local.masterKeyChanged, remote.masterKeyChanged),
        masterKeyChangeRec = selectValue(base.masterKeyChangeRec, local.masterKeyChangeRec, remote.masterKeyChangeRec),
        masterKeyChangeForce = selectValue(base.masterKeyChangeForce, local.masterKeyChangeForce, remote.masterKeyChangeForce),
        recycleBinEnabled = selectValue(base.recycleBinEnabled, local.recycleBinEnabled, remote.recycleBinEnabled),
        recycleBinUuid = selectValue(base.recycleBinUuid, local.recycleBinUuid, remote.recycleBinUuid),
        recycleBinChanged = selectValue(base.recycleBinChanged, local.recycleBinChanged, remote.recycleBinChanged),
        entryTemplatesGroup = selectValue(base.entryTemplatesGroup, local.entryTemplatesGroup, remote.entryTemplatesGroup),
        entryTemplatesGroupChanged = selectValue(base.entryTemplatesGroupChanged, local.entryTemplatesGroupChanged, remote.entryTemplatesGroupChanged),
        historyMaxItems = selectValue(base.historyMaxItems, local.historyMaxItems, remote.historyMaxItems),
        historyMaxSize = selectValue(base.historyMaxSize, local.historyMaxSize, remote.historyMaxSize),
        lastSelectedGroup = selectValue(base.lastSelectedGroup, local.lastSelectedGroup, remote.lastSelectedGroup),
        lastTopVisibleGroup = selectValue(base.lastTopVisibleGroup, local.lastTopVisibleGroup, remote.lastTopVisibleGroup),
        memoryProtection = selectValue(base.memoryProtection, local.memoryProtection, remote.memoryProtection),
        customData = mergeMapBySignature(base.customData, local.customData, remote.customData, ::customDataSignature)
    )

    private fun customDataSignature(value: CustomDataValue): String = "${value.value}|${value.lastModified}"

    private fun <T> selectValue(base: T, local: T, remote: T): T = when {
        local == base -> remote
        remote == base -> local
        local == remote -> local
        else -> local
    }

    private fun <K, V> mergeMapBySignature(
        base: Map<K, V>,
        local: Map<K, V>,
        remote: Map<K, V>,
        signature: (V) -> String
    ): Map<K, V> {
        val merged = remote.toMutableMap()
        val keys = linkedSetOf<K>().apply {
            addAll(base.keys)
            addAll(local.keys)
            addAll(remote.keys)
        }
        keys.forEach { key ->
            val baseValue = base[key]
            val localValue = local[key]
            val remoteValue = remote[key]
            val baseSignature = baseValue?.let(signature)
            val localSignature = localValue?.let(signature)
            val remoteSignature = remoteValue?.let(signature)
            val localChanged = localSignature != baseSignature
            val remoteChanged = remoteSignature != baseSignature
            when {
                !localChanged -> Unit
                !remoteChanged || localSignature == remoteSignature -> {
                    if (localValue == null) merged.remove(key) else merged[key] = localValue
                }
                else -> {
                    if (localValue == null) merged.remove(key) else merged[key] = localValue
                }
            }
        }
        return merged
    }

    private fun mergeDeletedObjects(
        base: List<DeletedObject>,
        local: List<DeletedObject>,
        remote: List<DeletedObject>
    ): List<DeletedObject> {
        val baseMap = base.associateBy(DeletedObject::id)
        val localMap = local.associateBy(DeletedObject::id)
        val remoteMap = remote.associateBy(DeletedObject::id)
        val merged = remoteMap.toMutableMap()
        val ids = linkedSetOf<UUID>().apply {
            addAll(baseMap.keys)
            addAll(localMap.keys)
            addAll(remoteMap.keys)
        }
        ids.forEach { id ->
            val baseValue = baseMap[id]
            val localValue = localMap[id]
            val remoteValue = remoteMap[id]
            if (localValue == baseValue) return@forEach
            if (remoteValue == baseValue || localValue == remoteValue) {
                if (localValue == null) merged.remove(id) else merged[id] = localValue
            } else if (localValue != null) {
                merged[id] = listOfNotNull(localValue, remoteValue).maxBy { it.deletionTime }
            }
        }
        return merged.values.sortedBy(DeletedObject::deletionTime)
    }

    private data class GroupRemoval(val group: Group, val removed: Group?)
    private data class EntryRemoval(val group: Group, val removed: Entry?)

    private fun removeGroup(group: Group, uuid: UUID): GroupRemoval {
        val index = group.groups.indexOfFirst { it.uuid == uuid }
        if (index >= 0) {
            val children = group.groups.toMutableList()
            val removed = children.removeAt(index)
            return GroupRemoval(group.copy(groups = children), removed)
        }
        group.groups.forEachIndexed { childIndex, child ->
            val removal = removeGroup(child, uuid)
            if (removal.removed != null) {
                val children = group.groups.toMutableList()
                children[childIndex] = removal.group
                return GroupRemoval(group.copy(groups = children), removal.removed)
            }
        }
        return GroupRemoval(group, null)
    }

    private fun removeEntry(group: Group, uuid: UUID): EntryRemoval {
        val index = group.entries.indexOfFirst { it.uuid == uuid }
        if (index >= 0) {
            val entries = group.entries.toMutableList()
            val removed = entries.removeAt(index)
            return EntryRemoval(group.copy(entries = entries), removed)
        }
        group.groups.forEachIndexed { childIndex, child ->
            val removal = removeEntry(child, uuid)
            if (removal.removed != null) {
                val children = group.groups.toMutableList()
                children[childIndex] = removal.group
                return EntryRemoval(group.copy(groups = children), removal.removed)
            }
        }
        return EntryRemoval(group, null)
    }

    private fun insertGroup(root: Group, parentUuid: UUID, group: Group): Group {
        if (root.uuid == parentUuid) return root.copy(groups = root.groups + group)
        return root.copy(groups = root.groups.map { child -> insertGroup(child, parentUuid, group) })
    }

    private fun upsertGroup(root: Group, group: Group, parentUuid: UUID): Group {
        val existing = findGroup(root, group.uuid)
        if (existing == null) return insertGroup(root, parentUuid, group)
        val detached = removeGroup(root, group.uuid).group
        val retained = group.copy(groups = existing.groups, entries = existing.entries)
        return insertGroup(detached, parentUuid.takeIf { findGroup(detached, it) != null } ?: detached.uuid, retained)
    }

    private fun insertEntry(root: Group, parentUuid: UUID, entry: Entry): Group {
        if (root.uuid == parentUuid) return root.copy(entries = root.entries + entry)
        return root.copy(groups = root.groups.map { child -> insertEntry(child, parentUuid, entry) })
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child -> findGroup(child, uuid)?.let { return it } }
        return null
    }

    private fun stripEntries(group: Group): Group = group.copy(
        entries = emptyList(),
        groups = group.groups.map(::stripEntries)
    )

    private fun cloneConflictGroup(group: Group): Group = group.copy(
        uuid = UUID.randomUUID(),
        name = group.name.ifBlank { "Unnamed group" } + " (local conflict)",
        groups = group.groups.map(::cloneConflictGroup),
        entries = group.entries.map { entry -> entry.copy(uuid = UUID.randomUUID()) }
    )

    private fun isDescendantOfHandledTree(
        uuid: UUID,
        groups: Map<UUID, LocatedGroup>,
        handledRoots: Set<UUID>
    ): Boolean {
        var current = groups[uuid]?.parentUuid
        val seen = mutableSetOf<UUID>()
        while (current != null && seen.add(current)) {
            if (current in handledRoots) return true
            current = groups[current]?.parentUuid
        }
        return false
    }

    private fun buildRemoteConflictCopy(entry: Entry): Entry {
        val title = entry.fields["Title"]?.content?.ifBlank { "Untitled" } ?: "Untitled"
        val fields = entry.fields.mapNotNull { (name, value) ->
            when (name) {
                "MonicaLocalId", "MonicaItemId" -> null
                "Title" -> name to EntryValue.Plain("$title (remote conflict)")
                "MonicaConflictCopy" -> name to EntryValue.Plain("true")
                else -> name to value
            }
        }.toMutableList()
        if (fields.none { it.first == "Title" }) fields += "Title" to EntryValue.Plain("$title (remote conflict)")
        if (fields.none { it.first == "MonicaConflictCopy" }) {
            fields += "MonicaConflictCopy" to EntryValue.Plain("true")
        }
        return entry.copy(
            uuid = UUID.randomUUID(),
            fields = EntryFields.of(*fields.toTypedArray())
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
