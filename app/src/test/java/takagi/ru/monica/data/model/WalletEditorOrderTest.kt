package takagi.ru.monica.data.model

import org.junit.Assert.*
import org.junit.Test

class WalletEditorOrderTest {
    @Test fun oldEntriesUseAvailableOrderAndStaleKeysCannotHideContent() {
        val available = listOf("billing", "extended", "custom", "notes")
        assertEquals(available, WalletEditorOrder.resolve(emptyList(), available))
        assertEquals(listOf("notes", "billing", "extended", "custom"),
            WalletEditorOrder.resolve(listOf("notes", "removed", "notes"), available))
        assertEquals(available, WalletEditorOrder.move(available, "wallet_primary", "notes"))
    }

    @Test fun movesKeepEverySectionOnceInBothDirections() {
        val available = listOf("billing", "extended", "custom", "notes")
        val moved = WalletEditorOrder.move(available, "notes", "billing")
        assertEquals(listOf("notes", "billing", "extended", "custom"), moved)
        assertEquals(available, WalletEditorOrder.move(moved, "notes", "custom"))
    }

    @Test fun bankCodecKeepsOrderAndSensitiveFieldsWhileOldPayloadDefaultsToEmpty() {
        val data = BankCardData("4111111111111111", "Synthetic", "01", "2030", pin = "0123",
            editorSectionOrder = listOf("notes", "extended", "billing"),
            customFields = listOf(SecureCustomField("Private", "synthetic-secret", SecureCustomFieldType.HIDDEN)))
        assertEquals(data, CardWalletDataCodec.parseBankCardData(CardWalletDataCodec.encodeBankCardData(data)))
        val old = """{"cardNumber":"4111111111111111","cardholderName":"Synthetic","expiryMonth":"01","expiryYear":"2030","pin":"0123"}"""
        val restored = requireNotNull(CardWalletDataCodec.parseBankCardData(old))
        assertEquals(emptyList<String>(), restored.editorSectionOrder)
        assertEquals("0123", restored.pin)
    }

    @Test fun documentCodecKeepsOrderWithoutChangingIdentityOrCustomFields() {
        val data = DocumentData(DocumentType.PASSPORT, "SYNTHETIC-001", "Synthetic", ssn = "synthetic-id",
            editorSectionOrder = listOf("notes", "identity", "custom"),
            customFields = listOf(SecureCustomField("Flag", "true", SecureCustomFieldType.BOOLEAN)))
        assertEquals(data, CardWalletDataCodec.parseDocumentData(CardWalletDataCodec.encodeDocumentData(data)))
        val old = """{"documentType":"PASSPORT","documentNumber":"SYNTHETIC-001","fullName":"Synthetic"}"""
        assertEquals(emptyList<String>(), requireNotNull(CardWalletDataCodec.parseDocumentData(old)).editorSectionOrder)
    }
}
