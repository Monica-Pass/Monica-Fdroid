package takagi.ru.monica.repository

import uniffi.mdbx_ffi.MdbxObjectLabelAssignmentSummary
import uniffi.mdbx_ffi.MdbxVault
import uniffi.mdbx_ffi.MdbxWriteCommand
import java.util.UUID

/** Synced native metadata; listing favorites never reveals credential payloads. */
internal object NativeApiTokenFavorites {
    private const val LABEL_NAME = "monica:api-token:favorite:v1"

    private fun labelIds(vault: MdbxVault, collectionId: String): Set<String> = buildSet {
        var cursor: String? = null
        do {
            val page = vault.listObjectLabelSummaries(collectionId, 100u, cursor)
            page.items.filter { !it.deleted && it.name == LABEL_NAME }.forEach { add(it.labelId) }
            cursor = page.nextCursor
        } while (cursor != null)
    }

    fun entryIds(vault: MdbxVault, collectionId: String): Set<String> = buildSet {
        // Concurrent devices may create separate marker labels. Read their union.
        for (labelId in labelIds(vault, collectionId)) {
            var cursor: String? = null
            do {
                val page = vault.listObjectLabelAssignmentSummariesByLabel(labelId, 100u, cursor)
                page.items.filterNot { it.deleted }.forEach { add(it.objectId) }
                cursor = page.nextCursor
            } while (cursor != null)
        }
    }

    fun assignments(vault: MdbxVault, entryId: String, collectionId: String): List<MdbxObjectLabelAssignmentSummary> {
        val labels = labelIds(vault, collectionId)
        if (labels.isEmpty()) return emptyList()
        return buildList {
            var cursor: String? = null
            do {
                val page = vault.listObjectLabelAssignmentSummariesByObject(entryId, 100u, cursor)
                addAll(page.items.filter { !it.deleted && it.labelId in labels })
                cursor = page.nextCursor
            } while (cursor != null)
        }
    }

    fun addCommands(vault: MdbxVault, entryId: String, collectionId: String, collectionExists: Boolean): List<MdbxWriteCommand> {
        val existing = if (collectionExists) labelIds(vault, collectionId).firstOrNull() else null
        val labelId = existing ?: UUID.randomUUID().toString()
        return buildList {
            if (existing == null) add(MdbxWriteCommand.CreateObjectLabel(
                labelId, collectionId, LABEL_NAME, "{\"kind\":\"monica-api-token-favorites\"}", 1u
            ))
            add(MdbxWriteCommand.AssignObjectLabel(UUID.randomUUID().toString(), entryId, labelId))
        }
    }
}
