package takagi.ru.monica.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MdbxMigrationEntryMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rewritesFolderAndPreservesPortableFields() {
        val source = MdbxStoredVaultEntry(
            entryId = "password:7",
            entryType = "login",
            title = "Example",
            payloadJson = """{"password_plain":"secret","custom":{"future":true},"mdbx_folder_id":"source-folder"}""",
            deleted = false
        )

        val rewritten = MdbxMigrationEntryMapper.rewrite(
            MdbxMigrationEntryPlan(source, "source-folder"),
            targetFolderId = "target-folder"
        )
        val payload = json.parseToJsonElement(rewritten.payloadJson).jsonObject

        assertEquals("password:7", payload["monica_entry_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("target-folder", payload["mdbx_folder_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("source-folder", payload["mdbx_migration_source_folder_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("secret", payload["password_plain"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, payload["custom"]?.jsonObject?.get("future")?.jsonPrimitive?.contentOrNull?.toBoolean())
    }

    @Test
    fun rootEntryRemovesStaleFolderAndPreservesDeletedState() {
        val source = MdbxStoredVaultEntry(
            entryId = "future:1",
            entryType = "future-type",
            title = "Future",
            payloadJson = """{"mdbx_folder_id":"stale","future_field":9}""",
            deleted = true
        )

        val rewritten = MdbxMigrationEntryMapper.rewrite(
            MdbxMigrationEntryPlan(source, null),
            targetFolderId = null
        )
        val payload = json.parseToJsonElement(rewritten.payloadJson).jsonObject

        assertFalse(payload.containsKey("mdbx_folder_id"))
        assertEquals("9", payload["future_field"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, rewritten.deleted)
    }
}
