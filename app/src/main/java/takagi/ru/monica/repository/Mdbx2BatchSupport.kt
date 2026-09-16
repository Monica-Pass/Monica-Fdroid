package takagi.ru.monica.repository

import okio.utf8Size
import uniffi.mdbx_ffi.MdbxFfiException
import uniffi.mdbx_ffi.MdbxObjectSummary
import uniffi.mdbx_ffi.MdbxVault
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.MdbxWriteOperationLimits

internal data class Mdbx2EntryMutationSnapshot(
    val activeCollectionIds: Set<String>,
    val objectsById: Map<String, MdbxObjectSummary>
)

internal data class Mdbx2WriteBatch(
    val operationId: String,
    val commandGroups: List<List<MdbxWriteCommand>>,
    val limits: MdbxWriteOperationLimits
) {
    val commands: List<MdbxWriteCommand> = commandGroups.flatten()
}

/** Leave room for encrypted fields and two levels of JSON byte-array encoding. */
internal fun mdbx2ImportPayloadBudget(limits: MdbxWriteOperationLimits): ULong =
    minOf(limits.maxPayloadBytes, 1024uL * 1024uL)

/** Only a native sync-size failure proves that the whole operation was rolled back. */
internal suspend fun executeMdbx2ImportBatch(
    batch: Mdbx2WriteBatch,
    execute: (Mdbx2WriteBatch) -> Unit,
    onCommitted: suspend (List<MdbxWriteCommand>) -> Unit,
) {
    try {
        execute(batch)
    } catch (failure: MdbxFfiException.Storage) {
        if (!failure.detail.startsWith("resource limit exceeded for sync delta payload bytes:") ||
            batch.commandGroups.size < 2) throw failure
        // CommitContext rolls entries, history, operation receipts and sync deltas back
        // together. Child operation IDs describe new intents; dependent entry commands
        // stay together. Never retry an unknown I/O failure or an acknowledgement error.
        val midpoint = batch.commandGroups.size / 2
        val halves = listOf(batch.commandGroups.take(midpoint), batch.commandGroups.drop(midpoint))
        halves.forEachIndexed { index, groups ->
            executeMdbx2ImportBatch(
                batch.copy(operationId = "${batch.operationId}-${index + 1}", commandGroups = groups),
                execute,
                onCommitted,
            )
        }
        return
    }
    onCommitted(batch.commands)
}

internal fun MdbxVault.loadMdbx2EntryMutationSnapshot(
    requestedObjectIds: Set<String>,
    preferredCollectionIds: Set<String>,
    rootCollectionId: String
): Mdbx2EntryMutationSnapshot {
    val activeCollectionIds = linkedSetOf(rootCollectionId)
    var collectionCursor: String? = null
    val seenCollectionCursors = mutableSetOf<String>()
    while (true) {
        val page = listCollectionSummaries(MDBX2_SUMMARY_PAGE_SIZE, collectionCursor)
        page.items.asSequence()
            .filterNot { it.deleted }
            .mapTo(activeCollectionIds) { it.collectionId }
        val nextCursor = page.nextCursor ?: break
        check(page.items.isNotEmpty() && seenCollectionCursors.add(nextCursor)) {
            "MDBX2 collection summary pagination did not advance"
        }
        collectionCursor = nextCursor
    }

    if (requestedObjectIds.isEmpty()) {
        return Mdbx2EntryMutationSnapshot(activeCollectionIds, emptyMap())
    }

    val orderedCollectionIds = linkedSetOf<String>().apply {
        preferredCollectionIds.filterTo(this) { it in activeCollectionIds }
        add(rootCollectionId)
        addAll(activeCollectionIds)
    }
    val remainingObjectIds = requestedObjectIds.toMutableSet()
    val objectsById = HashMap<String, MdbxObjectSummary>(requestedObjectIds.size)

    orderedCollectionIds.forEach { collectionId ->
        collectRequestedObjectSummaries(
            collectionId = collectionId,
            deleted = false,
            remainingObjectIds = remainingObjectIds,
            destination = objectsById
        )
        if (remainingObjectIds.isEmpty()) return@forEach
    }
    if (remainingObjectIds.isNotEmpty()) {
        orderedCollectionIds.forEach { collectionId ->
            collectRequestedObjectSummaries(
                collectionId = collectionId,
                deleted = true,
                remainingObjectIds = remainingObjectIds,
                destination = objectsById
            )
            if (remainingObjectIds.isEmpty()) return@forEach
        }
    }

    return Mdbx2EntryMutationSnapshot(activeCollectionIds, objectsById)
}

internal fun planMdbx2WriteBatches(
    commandGroups: List<List<MdbxWriteCommand>>,
    baseOperationId: String,
    defaultLimits: MdbxWriteOperationLimits,
    useDefaultBatchLimits: Boolean = false,
): List<Mdbx2WriteBatch> {
    require(baseOperationId.isNotBlank()) { "MDBX2 batch operation ID cannot be empty" }
    if (commandGroups.isEmpty()) return emptyList()

    val chunks = mutableListOf<List<List<MdbxWriteCommand>>>()
    var currentGroups = mutableListOf<List<MdbxWriteCommand>>()
    var currentCommands = mutableListOf<MdbxWriteCommand>()
    var currentPayloadBytes = 0L
    var currentIntentBytes = 0L
    val commandBudget = if (useDefaultBatchLimits) {
        minOf(defaultLimits.maxCommands, MDBX2_HARD_MAX_WRITE_COMMANDS.toULong()).toInt()
    } else MDBX2_HARD_MAX_WRITE_COMMANDS
    val payloadBudget = if (useDefaultBatchLimits) {
        mdbx2ImportPayloadBudget(defaultLimits).toLong()
    } else MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES
    val intentBudget = if (useDefaultBatchLimits) {
        minOf(defaultLimits.maxIntentBytes, MDBX2_HARD_MAX_WRITE_INTENT_BYTES.toULong()).toLong()
    } else MDBX2_HARD_MAX_WRITE_INTENT_BYTES

    fun flushCurrentChunk() {
        if (currentCommands.isEmpty()) return
        chunks += currentGroups
        currentGroups = mutableListOf()
        currentCommands = mutableListOf()
        currentPayloadBytes = 0L
        currentIntentBytes = 0L
    }

    commandGroups.forEach { group ->
        if (group.isEmpty()) return@forEach
        group.forEach(MdbxWriteCommand::validatePresentationTitle)
        val groupPayloadBytes = group.sumOf(MdbxWriteCommand::payloadBytes)
        val groupIntentBytes = group.sumOf(MdbxWriteCommand::intentBytesUpperBound)
        val maxCommandPayload = group.maxOf(MdbxWriteCommand::payloadBytes)
        require(group.size <= MDBX2_HARD_MAX_WRITE_COMMANDS) {
            "One MDBX2 entry mutation exceeds the native command ceiling"
        }
        require(groupPayloadBytes <= MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES) {
            "One MDBX2 entry mutation exceeds the native payload ceiling"
        }
        require(maxCommandPayload <= MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES_PER_COMMAND) {
            "MDBX2 command payload exceeds the native per-command ceiling"
        }
        require(groupIntentBytes <= MDBX2_HARD_MAX_WRITE_INTENT_BYTES) {
            "One MDBX2 entry mutation exceeds the native intent ceiling"
        }
        // A large indivisible entry may need a higher per-command limit. Isolate it;
        // never raise every import batch to the engine's hard ceilings.
        val dedicatedBatch = useDefaultBatchLimits &&
            maxCommandPayload.toULong() > defaultLimits.maxPayloadBytesPerCommand
        val wouldExceedCommands =
            currentCommands.size + group.size > commandBudget
        val wouldExceedPayload =
            currentPayloadBytes + groupPayloadBytes > payloadBudget
        val wouldExceedIntent = currentIntentBytes + groupIntentBytes > intentBudget
        if (currentCommands.isNotEmpty() &&
            (dedicatedBatch || wouldExceedCommands || wouldExceedPayload || wouldExceedIntent)) {
            flushCurrentChunk()
        }
        currentCommands += group
        currentGroups += group
        currentPayloadBytes += groupPayloadBytes
        currentIntentBytes += groupIntentBytes
        if (dedicatedBatch) flushCurrentChunk()
    }
    flushCurrentChunk()

    return chunks.mapIndexed { index, groups ->
        val commands = groups.flatten()
        val payloadBytes = commands.sumOf(MdbxWriteCommand::payloadBytes)
        val maxPayloadBytesPerCommand = commands.maxOfOrNull(MdbxWriteCommand::payloadBytes) ?: 0L
        require(maxPayloadBytesPerCommand <= MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES_PER_COMMAND) {
            "MDBX2 command payload exceeds the native per-command ceiling"
        }
        val estimatedIntentBytes = commands.sumOf(MdbxWriteCommand::intentBytesUpperBound)
        require(estimatedIntentBytes <= MDBX2_HARD_MAX_WRITE_INTENT_BYTES) {
            "MDBX2 write intent exceeds the native ceiling"
        }
        Mdbx2WriteBatch(
            operationId = if (chunks.size == 1) {
                baseOperationId
            } else {
                "$baseOperationId-${index + 1}-of-${chunks.size}"
            },
            commandGroups = groups,
            limits = MdbxWriteOperationLimits(
                maxCommands = maxOf(defaultLimits.maxCommands, commands.size.toULong()),
                maxPayloadBytesPerCommand = maxOf(
                    defaultLimits.maxPayloadBytesPerCommand,
                    maxPayloadBytesPerCommand.toULong()
                ),
                maxPayloadBytes = maxOf(defaultLimits.maxPayloadBytes, payloadBytes.toULong()),
                maxIntentBytes = maxOf(defaultLimits.maxIntentBytes, estimatedIntentBytes.toULong())
            )
        )
    }
}

private fun MdbxVault.collectRequestedObjectSummaries(
    collectionId: String,
    deleted: Boolean,
    remainingObjectIds: MutableSet<String>,
    destination: MutableMap<String, MdbxObjectSummary>
) {
    if (remainingObjectIds.isEmpty()) return
    var cursor: String? = null
    val seenCursors = mutableSetOf<String>()
    while (remainingObjectIds.isNotEmpty()) {
        val page = if (deleted) {
            listDeletedObjectSummaries(collectionId, null, MDBX2_SUMMARY_PAGE_SIZE, cursor)
        } else {
            listObjectSummaries(collectionId, null, MDBX2_SUMMARY_PAGE_SIZE, cursor)
        }
        page.items.forEach { summary ->
            if (remainingObjectIds.remove(summary.objectId)) {
                destination[summary.objectId] = summary
            }
        }
        val nextCursor = page.nextCursor ?: break
        check(page.items.isNotEmpty() && seenCursors.add(nextCursor)) {
            "MDBX2 object summary pagination did not advance"
        }
        cursor = nextCursor
    }
}

private fun MdbxWriteCommand.validatePresentationTitle() {
    val value = when (this) {
        is MdbxWriteCommand.CreateEntry -> title
        is MdbxWriteCommand.UpdateEntry -> title
        is MdbxWriteCommand.CreateProject -> title
        is MdbxWriteCommand.CreateProjectWithParent -> title
        is MdbxWriteCommand.RenameProject -> title
        else -> return
    }
    // Match Rust's MAX_PRESENTATION_TITLE_BYTES: accepting a larger title would
    // create a record that the bounded list/read APIs cannot return.
    require(value.utf8Size() <= MDBX2_MAX_PRESENTATION_TITLE_BYTES) {
        "MDBX2 title exceeds the native presentation limit of 64 KiB"
    }
}

private fun MdbxWriteCommand.payloadBytes(): Long = when (this) {
    is MdbxWriteCommand.CreateEntry -> payloadJson.utf8Size()
    is MdbxWriteCommand.UpdateEntry -> payloadJson.utf8Size()
    is MdbxWriteCommand.CreateObjectRelation -> payloadJson.utf8Size()
    is MdbxWriteCommand.UpdateObjectRelation -> payloadJson.utf8Size()
    is MdbxWriteCommand.CreateObjectLabel -> payloadJson.utf8Size()
    is MdbxWriteCommand.UpdateObjectLabel -> payloadJson.utf8Size()
    else -> 0L
}

/** Rust hashes JSON-serialized commands, so nested JSON escapes and titles also count. */
private fun MdbxWriteCommand.intentBytesUpperBound(): Long {
    val fields = when (this) {
        is MdbxWriteCommand.CreateProject -> listOf(projectId, title)
        is MdbxWriteCommand.CreateProjectWithParent -> listOfNotNull(projectId, title, parentProjectId)
        is MdbxWriteCommand.RenameProject -> listOf(projectId, title)
        is MdbxWriteCommand.MoveProject -> listOfNotNull(projectId, parentProjectId)
        is MdbxWriteCommand.DeleteProject -> listOf(projectId)
        is MdbxWriteCommand.RestoreProject -> listOfNotNull(projectId, parentProjectId)
        is MdbxWriteCommand.CreateEntry -> listOf(entryId, projectId, entryType, title, payloadJson)
        is MdbxWriteCommand.UpdateEntry -> listOf(entryId, projectId, entryType, title, payloadJson)
        is MdbxWriteCommand.DeleteEntry -> listOf(entryId, projectId)
        is MdbxWriteCommand.RestoreEntry -> listOf(entryId, projectId)
        is MdbxWriteCommand.MoveEntry -> listOf(entryId, projectId, targetProjectId)
        is MdbxWriteCommand.CreateObjectRelation -> listOf(relationId, sourceObjectId, targetObjectId, relationKind, payloadJson)
        is MdbxWriteCommand.UpdateObjectRelation -> listOf(relationId, relationKind, payloadJson)
        is MdbxWriteCommand.DeleteObjectRelation -> listOf(relationId)
        is MdbxWriteCommand.CreateObjectLabel -> listOf(labelId, collectionId, name, payloadJson)
        is MdbxWriteCommand.UpdateObjectLabel -> listOf(labelId, name, payloadJson)
        is MdbxWriteCommand.DeleteObjectLabel -> listOf(labelId)
        is MdbxWriteCommand.AssignObjectLabel -> listOf(assignmentId, objectId, labelId)
        is MdbxWriteCommand.RemoveObjectLabelAssignment -> listOf(assignmentId)
    }
    return MDBX2_ESTIMATED_INTENT_OVERHEAD_PER_COMMAND + fields.sumOf { value ->
        // Count bytes without allocating another complete plaintext UTF-8 buffer.
        value.utf8Size() + value.sumOf { character ->
            when (character) {
                '"', '\\', '\b', '\t', '\n', '\r', '\u000c' -> 1L
                in '\u0000'..'\u001f' -> 5L
                else -> 0L
            }
        }
    }
}

private const val MDBX2_SUMMARY_PAGE_SIZE = 200u
private const val MDBX2_MAX_PRESENTATION_TITLE_BYTES = 64L * 1024L
private const val MDBX2_HARD_MAX_WRITE_COMMANDS = 4_096
private const val MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES_PER_COMMAND = 16L * 1024L * 1024L
private const val MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES = 64L * 1024L * 1024L
private const val MDBX2_HARD_MAX_WRITE_INTENT_BYTES = 128L * 1024L * 1024L
private const val MDBX2_ESTIMATED_INTENT_OVERHEAD_PER_COMMAND = 4L * 1024L
