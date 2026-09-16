package takagi.ru.monica.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApiTokenMetadataTest {
    private val original = """{"schema":"monica.api-token.fields.v1","notes":"line one\nline two","extension":{"version":2},"custom_fields":[{"id":-1,"title":"Scope","value":"synthetic protected field","protected":true,"future":"preserve"}]}"""

    @Test fun editsPreserveProtectedFieldsAndUnknownExtensions() {
        val fields = ApiTokenMetadata.customFields(original)
        assertTrue(fields.single().isProtected)
        val edited = ApiTokenMetadata.withCustomFields(original, fields.map { it.copy(title = "New scope") })
        val decoded = ApiTokenMetadata.decode(edited)!!
        assertTrue(ApiTokenMetadata.isValid(edited))
        assertEquals(ApiTokenMetadata.decode(original)?.get("extension"), decoded["extension"])
        assertEquals("preserve", decoded["custom_fields"]!!.jsonArray.single().jsonObject["future"]!!.jsonPrimitive.content)
        assertEquals("line one\nline two", ApiTokenMetadata.notes(edited, "fallback"))
        assertFalse(NativeApiTokenExtras("label", "assignment", original).toString().contains("synthetic protected field"))
    }

    @Test fun oversizedDraftCanBeCorrectedWithoutLosingFields() {
        val tooLong = "x".repeat(ApiTokenMetadata.MAX_BYTES)
        val oversized = ApiTokenMetadata.withNotes(original, tooLong)
        assertFalse(ApiTokenMetadata.isValid(oversized))
        assertEquals(tooLong, ApiTokenMetadata.notes(oversized, "fallback"))
        assertEquals(ApiTokenMetadata.customFields(original), ApiTokenMetadata.customFields(oversized))
        val corrected = ApiTokenMetadata.withNotes(oversized, "Corrected")
        assertTrue(ApiTokenMetadata.isValid(corrected))
        assertEquals(ApiTokenMetadata.decode(original)?.get("custom_fields"), ApiTokenMetadata.decode(corrected)?.get("custom_fields"))
        val foreign = original.replace("fields.v1", "fields.v2")
        assertEquals(foreign, ApiTokenMetadata.withNotes(foreign, "Must not overwrite"))
    }

    @Test fun newFieldsAvoidPersistedDraftIdsAndDuplicateIdsAreRejected() {
        val last = CustomFieldDraft.nextTempId()
        val next = CustomFieldDraft.nextTempId(listOf(last - 1, last - 2))
        assertEquals(last - 3, next)
        val field = ApiTokenMetadata.customFields(original).single()
        assertFalse(ApiTokenMetadata.isValid(ApiTokenMetadata.withCustomFields(original, listOf(field, field))))
    }
}
