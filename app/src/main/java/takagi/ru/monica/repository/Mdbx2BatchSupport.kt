package takagi.ru.monica.repository

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
    val commands: List<MdbxWriteCommand>,
    val limits: MdbxWriteOperationLimits
)

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
    defaultLimits: MdbxWriteOperationLimits
): List<Mdbx2WriteBatch> {
    require(baseOperationId.isNotBlank()) { "MDBX2 batch operation ID cannot be empty" }
    if (commandGroups.isEmpty()) return emptyList()

    val chunks = mutableListOf<List<MdbxWriteCommand>>()
    var currentCommands = mutableListOf<MdbxWriteCommand>()
    var currentPayloadBytes = 0L

    fun flushCurrentChunk() {
        if (currentCommands.isEmpty()) return
        chunks += currentCommands
        currentCommands = mutableListOf()
        currentPayloadBytes = 0L
    }

    commandGroups.forEach { group ->
        if (group.isEmpty()) return@forEach
        val groupPayloadBytes = group.sumOf(MdbxWriteCommand::payloadBytes)
        require(group.size <= MDBX2_HARD_MAX_WRITE_COMMANDS) {
            "One MDBX2 entry mutation exceeds the native command ceiling"
        }
        require(groupPayloadBytes <= MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES) {
            "One MDBX2 entry mutation exceeds the native payload ceiling"
        }
        val wouldExceedCommands =
            currentCommands.size + group.size > MDBX2_HARD_MAX_WRITE_COMMANDS
        val wouldExceedPayload =
            currentPayloadBytes + groupPayloadBytes > MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES
        if (currentCommands.isNotEmpty() && (wouldExceedCommands || wouldExceedPayload)) {
            flushCurrentChunk()
        }
        currentCommands += group
        currentPayloadBytes += groupPayloadBytes
    }
    flushCurrentChunk()

    return chunks.mapIndexed { index, commands ->
        val payloadBytes = commands.sumOf(MdbxWriteCommand::payloadBytes)
        val maxPayloadBytesPerCommand = commands.maxOfOrNull(MdbxWriteCommand::payloadBytes) ?: 0L
        require(maxPayloadBytesPerCommand <= MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES_PER_COMMAND) {
            "MDBX2 command payload exceeds the native per-command ceiling"
        }
        val estimatedIntentBytes = payloadBytes +
            commands.size.toLong() * MDBX2_ESTIMATED_INTENT_OVERHEAD_PER_COMMAND
        require(estimatedIntentBytes <= MDBX2_HARD_MAX_WRITE_INTENT_BYTES) {
            "MDBX2 write intent exceeds the native ceiling"
        }
        Mdbx2WriteBatch(
            operationId = if (chunks.size == 1) {
                baseOperationId
            } else {
                "$baseOperationId-${index + 1}-of-${chunks.size}"
            },
            commands = commands,
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

private fun MdbxWriteCommand.payloadBytes(): Long = when (this) {
    is MdbxWriteCommand.CreateEntry -> payloadJson.toByteArray(Charsets.UTF_8).size.toLong()
    is MdbxWriteCommand.UpdateEntry -> payloadJson.toByteArray(Charsets.UTF_8).size.toLong()
    else -> 0L
}

private const val MDBX2_SUMMARY_PAGE_SIZE = 200u
private const val MDBX2_HARD_MAX_WRITE_COMMANDS = 4_096
private const val MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES_PER_COMMAND = 16L * 1024L * 1024L
private const val MDBX2_HARD_MAX_WRITE_PAYLOAD_BYTES = 64L * 1024L * 1024L
private const val MDBX2_HARD_MAX_WRITE_INTENT_BYTES = 128L * 1024L * 1024L
private const val MDBX2_ESTIMATED_INTENT_OVERHEAD_PER_COMMAND = 4L * 1024L
