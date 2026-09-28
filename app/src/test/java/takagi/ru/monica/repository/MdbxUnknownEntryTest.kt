package takagi.ru.monica.repository

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import takagi.ru.monica.ui.screens.mdbxUnknownFields

class MdbxUnknownEntryTest {
    @Test fun futureTypeProjectsWithoutCachingOrReinterpretingSecrets() {
        val stored = MdbxStoredVaultEntry("future-id", "com.example.recovery-kit", "Recovery kit",
            """{"username":"private-user","token":"synthetic-secret","codes":["one",null]}""", false, "nested-folder")
        val row = MdbxUnknownEntry.project(7, stored, null)
        assertEquals("future-id", row.replicaGroupId)
        assertEquals("nested-folder", row.mdbxFolderId)
        assertTrue(row.password.isEmpty() && row.notes.isEmpty() && row.username.isEmpty() && row.website.isEmpty())
        assertTrue(MdbxUnknownEntry.isProjection(row))
        assertTrue(runCatching { MdbxUnknownEntry.requireEditable(row) }.isFailure)
        assertEquals(row.copy(id = 42), MdbxUnknownEntry.project(7, stored, row.copy(id = 42)))
    }

    @Test fun supportedNativeEditorsRemainDistinctAndTypeNamesAreCaseSensitive() {
        listOf("api-token", "steam-mafile", "login", "totp", "passkey", "note", "card").forEach {
            assertFalse(it, MdbxUnknownEntry.isUnknown(it))
        }
        assertTrue(MdbxUnknownEntry.projectsAsPassword("custom-v17"))
        assertTrue(MdbxUnknownEntry.isUnknown("LOGIN"))
    }

    @Test fun rendererRetainsNullEmptyArraysNestedObjectsAndLargeNumbers() {
        val fields = mdbxUnknownFields("""{"empty":"","null":null,"number":9007199254740993,"codes":[1,false,{"未知":"值"}]}""").toMap()
        assertEquals("", fields["empty"])
        assertEquals("null", fields["null"])
        assertEquals("9007199254740993", fields["number"])
        assertTrue(fields.getValue("codes").contains("未知"))
        val emptyObject = mdbxUnknownFields("{}").single()
        assertEquals("JSON", emptyObject.first)
        assertEquals(Json.parseToJsonElement("{}"), Json.parseToJsonElement(emptyObject.second))
        assertEquals(listOf("JSON" to "legacy raw payload"), mdbxUnknownFields("legacy raw payload"))
    }
}
