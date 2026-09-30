package takagi.ru.monica.data.model

import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType

class DeferredEmbeddedContentSaveTest {
    @Test fun oldCopyRemainsPublishedUntilAllNewAssetsAreAvailable() {
        val old = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.NOTE, title = "old", itemData = "{}"))
        val next = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.NOTE, title = "new", itemData = "{}"))
        val previous = EmbeddedWalletContent.put(emptyList(), old)
        val desired = EmbeddedWalletContent.put(previous, next) + CustomFieldDraft(title = "ordinary", value = "new ordinary value")
        val session = DeferredEmbeddedContentSave(setOf(next.id))
        val initial = session.initialFields(desired, previous)
        assertEquals(old.encode(), initial.single { EmbeddedWalletContent.isMetadata(it.title) }.value)
        assertEquals("new ordinary value", initial.single { it.title == "ordinary" }.value)
        assertTrue(session.replacesNote(desired))
        assertNull(session.commit(123))
        session.record(123, desired, null)
        assertEquals(next.encode(), session.commit(123)!!.fields.single { EmbeddedWalletContent.isMetadata(it.title) }.value)
    }
    @Test fun newEntryPublishesNoDanglingAssetReferencesBeforeUpload() {
        val card = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = "card", itemData = "{}"))
        val fields = EmbeddedWalletContent.put(emptyList(), card)
        val session = DeferredEmbeddedContentSave(setOf(card.id))
        assertTrue(session.initialFields(fields, emptyList()).isEmpty())
        assertFalse(session.replacesNote(fields))
        session.record(1, fields, 77)
        session.record(2, fields, 88)
        assertEquals(77L, session.commit(1)?.boundNoteId)
        assertEquals(88L, session.commit(2)?.boundNoteId)
    }
}
