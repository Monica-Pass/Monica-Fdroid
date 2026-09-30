package takagi.ru.monica.ui

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.EntryContentFields

class EntryContentCompatibilityTest {
    @Test fun editingKnownFieldPreservesUnknownsIdsAndProtection() {
        val unknown = CustomFieldDraft(id = 21, title = "monica.content.future.blob", value = "{opaque:1}", isProtected = true)
        val pin = CustomFieldDraft(id = 22, title = EntryContentFields.key("PAYMENT", "pin"), value = "1234", isProtected = true)
        val updated = EntryContentFields.update(listOf(unknown, pin), "PAYMENT", "pin", "5678", false)
        assertEquals(unknown, updated[0])
        assertEquals(pin.copy(value = "5678"), updated[1])
        val cleared = EntryContentFields.update(updated, "PAYMENT", "pin", "", true)
        assertEquals(unknown, cleared[0])
        assertEquals(pin.copy(value = ""), cleared[1])
    }
    @Test fun reorderingRetainsFutureSectionsAndDoesNotDuplicateMetadata() {
        val order = CustomFieldDraft(id = 30, title = EntryContentFields.ORDER, value = "FUTURE,PAYMENT,CONTACT")
        val fields = EntryContentFields.withOrder(listOf(order), listOf("CONTACT", "PAYMENT"))
        assertEquals(listOf("CONTACT", "PAYMENT", "FUTURE"), EntryContentFields.order(fields))
        assertEquals(30L, fields.single().id)
        assertEquals(fields, EntryContentFields.withOrder(fields, listOf("CONTACT", "PAYMENT")))
    }
}
