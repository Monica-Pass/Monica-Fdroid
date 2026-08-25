package takagi.ru.monica.notes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.model.SecureCustomField
import takagi.ru.monica.data.model.SecureCustomFieldType

class NoteCustomFieldsCodecTest {
    @Test
    fun `round trips note custom fields without changing regular note content`() {
        val fields = listOf(
            SecureCustomField("Account ID", "42"),
            SecureCustomField("Recovery", "hidden", SecureCustomFieldType.HIDDEN)
        )

        val (encoded, fallback) = NoteContentCodec.encode(
            content = "clean note",
            tags = listOf("tag"),
            isMarkdown = true,
            customFields = fields
        )
        val decoded = NoteContentCodec.decode(encoded, fallback)

        assertEquals("clean note", decoded.content)
        assertEquals(fields, decoded.customFields)
    }

    @Test
    fun `legacy notes have no custom field section`() {
        val decoded = NoteContentCodec.decode(
            itemData = "{\"content\":\"legacy\",\"tags\":[],\"isMarkdown\":false}",
            fallbackNotes = ""
        )

        assertEquals("legacy", decoded.content)
        assertTrue(decoded.customFields.isEmpty())
    }
}
