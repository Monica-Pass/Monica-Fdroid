package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem

class EmbeddedWalletContentTest {
    @Test fun completeContentAndUnknownFieldsSurviveEditingAndPortableRoundTrip() {
        val source = SecureItem(id = 900, itemType = ItemType.BANK_CARD, title = "Card", notes = "Recovery notes",
            itemData = """{"cardNumber":"1234","pin":"0123","iban":"IBAN","cardFace":{"theme":"blue","futureFlag":true},"future":{"nested":[1,2,3]}}""",
            keepassDatabaseId = 33, keepassEntryUuid = "source-uuid", imagePaths = "source-private-path")
        val original = EmbeddedWalletContent.create(source)
        val edited = original.edited("Edited", source.notes,
            Json.parseToJsonElement("""{"cardNumber":"5678","cardFace":{"theme":"red"}}""").jsonObject)
        val fields = EmbeddedWalletContent.put(listOf(CustomFieldDraft(title = "Other", value = "Keep")), edited)
        assertTrue(fields.last().isProtected)
        val restored = (EmbeddedWalletContent.read(fields.last().value) as EmbeddedWalletContent.ReadResult.Available).snapshot
        assertEquals("Keep", fields.first().value)
        assertEquals("0123", restored.itemData["pin"]?.jsonPrimitive?.content)
        assertEquals(true, restored.itemData["cardFace"]?.jsonObject?.get("futureFlag")?.jsonPrimitive?.boolean)
        assertEquals(original.itemData["future"], restored.itemData["future"])
        assertEquals("1234", original.itemData["cardNumber"]?.jsonPrimitive?.content)
        assertEquals("Recovery notes", restored.notes)
        assertFalse(restored.encode().contains("source-uuid"))
        assertFalse(restored.encode().contains("source-private-path"))
        assertEquals(0L, restored.displayItem().id)
        assertNull(restored.displayItem().keepassDatabaseId)
    }

    @Test fun unsupportedOrDamagedContentCannotBeSilentlyOverwritten() {
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = "Card", itemData = "{}"))
        for (raw in listOf("broken", snapshot.encode().replace("\"version\":1", "\"version\":2"))) {
            assertEquals(raw, (EmbeddedWalletContent.read(raw) as EmbeddedWalletContent.ReadResult.Unavailable).original)
            assertThrows(IllegalArgumentException::class.java) {
                EmbeddedWalletContent.put(listOf(CustomFieldDraft(title = EmbeddedWalletContent.fieldName(snapshot.kind), value = raw)), snapshot)
            }
        }
    }

    @Test fun allWalletKindsRetainTheirOwnFullPayload() {
        EmbeddedWalletContent.Kind.entries.forEach { kind ->
            val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = kind.itemType,
                title = kind.name, notes = "Notes", itemData = """{"arbitrary":"full data"}"""))
            assertEquals(kind, snapshot.kind)
            assertEquals("full data", snapshot.itemData["arbitrary"]?.jsonPrimitive?.content)
        }
    }

    @Test fun assetsUsePortableNamesAndPreserveOriginalDisplayNames() {
        val asset = EmbeddedWalletContent.Asset("wallet-abc", "statement.pdf", "application/pdf",
            EmbeddedWalletContent.AssetRole.ATTACHMENT, 42, "a".repeat(64))
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.DOCUMENT, title = "ID", itemData = "{}"))
            .withAssets(listOf(asset))
        assertEquals(listOf(asset), (EmbeddedWalletContent.read(snapshot.encode()) as EmbeddedWalletContent.ReadResult.Available).snapshot.assets)
        assertThrows(IllegalArgumentException::class.java) { snapshot.withAssets(listOf(asset, asset)) }
        assertThrows(IllegalArgumentException::class.java) { asset.copy(name = "../../file") }
    }
    @Test fun editingKnownCustomFieldsRetainsFutureTypesAndExtensions() {
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = "Card",
            itemData = """{"customFields":[{"label":"Known","value":"old","type":"TEXT","future":7},{"label":"Future","value":{"x":1},"type":"FUTURE"}]}"""))
        val updated = snapshot.edited("Card", "", Json.parseToJsonElement(
            """{"customFields":[{"label":"Known","value":"new","type":"TEXT"}]}""").jsonObject)
        val fields = updated.itemData.getValue("customFields").jsonArray
        assertEquals(2, fields.size)
        assertEquals(7, fields[0].jsonObject["future"]?.jsonPrimitive?.int)
        assertEquals("new", fields[0].jsonObject["value"]?.jsonPrimitive?.content)
        assertEquals(snapshot.itemData.getValue("customFields").jsonArray[1], fields[1])
    }

}
