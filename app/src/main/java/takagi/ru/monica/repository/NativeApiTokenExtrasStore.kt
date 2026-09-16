package takagi.ru.monica.repository

import takagi.ru.monica.data.ApiTokenMetadata
import takagi.ru.monica.data.NativeApiTokenExtras
import uniffi.mdbx_ffi.MdbxObjectMetadataDisclosureLimits
import uniffi.mdbx_ffi.MdbxVault
import uniffi.mdbx_ffi.MdbxWriteCommand
import java.util.UUID

/** Accessed only when opening or changing one token; never by the list index. */
internal object NativeApiTokenExtrasStore {
    private const val LABEL_NAME = "monica:api-token:fields:v1"

    fun read(vault: MdbxVault, entryId: String): NativeApiTokenExtras? {
        val matches = mutableListOf<NativeApiTokenExtras>()
        var cursor: String? = null
        do {
            val page = vault.listObjectLabelAssignmentSummariesByObject(entryId, 100u, cursor)
            for (assignment in page.items.filterNot { it.deleted }) {
                val summary = vault.getObjectLabelSummary(assignment.labelId) ?: continue
                if (summary.deleted || summary.name != LABEL_NAME) continue
                val label = vault.revealObjectLabelWithLimits(summary.labelId,
                    MdbxObjectMetadataDisclosureLimits(ApiTokenMetadata.MAX_BYTES.toULong())).label
                    ?: error("Token custom fields could not be disclosed")
                matches += NativeApiTokenExtras(label.labelId, assignment.assignmentId, label.payloadJson)
            }
            cursor = page.nextCursor
        } while (cursor != null)
        check(matches.size <= 1) { "Token custom fields have conflicting copies; resolve before editing" }
        return matches.singleOrNull()
    }

    fun writeCommands(entryId: String, collectionId: String, original: NativeApiTokenExtras?,
        payload: String, moving: Boolean): List<MdbxWriteCommand> {
        if (original == null && payload == ApiTokenMetadata.empty()) return emptyList()
        if (original != null && !moving && payload == original.payload) return emptyList()
        return if (original != null && !moving) listOf(MdbxWriteCommand.UpdateObjectLabel(
            original.labelId, LABEL_NAME, payload, 1u
        )) else {
            val labelId = UUID.randomUUID().toString()
            listOf(MdbxWriteCommand.CreateObjectLabel(labelId, collectionId, LABEL_NAME, payload, 1u),
                MdbxWriteCommand.AssignObjectLabel(UUID.randomUUID().toString(), entryId, labelId))
        }
    }
}
