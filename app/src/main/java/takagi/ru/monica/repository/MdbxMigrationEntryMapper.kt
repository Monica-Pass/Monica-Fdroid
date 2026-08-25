package takagi.ru.monica.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal object MdbxMigrationEntryMapper {
    private const val SOURCE_FOLDER_FIELD = "mdbx_migration_source_folder_id"
    private val json = Json { ignoreUnknownKeys = true }

    fun rewrite(
        plan: MdbxMigrationEntryPlan,
        targetFolderId: String?
    ): MdbxStoredVaultEntry {
        val fields = json.parseToJsonElement(plan.entry.payloadJson).jsonObject.toMutableMap()
        fields["monica_entry_id"] = JsonPrimitive(plan.entry.entryId)
        if (targetFolderId.isNullOrBlank()) {
            fields.remove("mdbx_folder_id")
        } else {
            fields["mdbx_folder_id"] = JsonPrimitive(targetFolderId)
        }
        plan.sourceFolderId?.let { fields[SOURCE_FOLDER_FIELD] = JsonPrimitive(it) }
        return plan.entry.copy(payloadJson = JsonObject(fields).toString())
    }
}
