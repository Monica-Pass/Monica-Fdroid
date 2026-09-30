package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem

class EmbeddedDocumentEditorDataTest {
    private fun snapshot() = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.DOCUMENT,
        title = "Passport", notes = "private notes", isFavorite = true, itemData = """{
          "documentType":"PASSPORT","documentNumber":"000123","fullName":"张伟",
          "title":"Dr","firstName":"Wei","middleName":"M","lastName":"Zhang",
          "issuedDate":"2020-01-02","expiryDate":"2030-01-02","issuedBy":"Authority","nationality":"CN",
          "address1":"One","address2":"Two","address3":"Three","city":"City","stateProvince":"State",
          "postalCode":"00100","country":"China","company":"Company","email":"a@example.com",
          "phone":"+123","ssn":"0000","username":"login","passportNumber":"P0001","licenseNumber":"L0002",
          "additionalInfo":"extra","future":{"nested":[1,true,"secret"]},
          "customFields":[{"label":"Hidden","value":"secret","type":"HIDDEN","futurePolicy":{"keep":true}},
            {"label":"Checkbox","value":"true","type":"BOOLEAN"},
            {"label":"Future field","value":"future secret","type":"FUTURE"}],
          "cardFace":{"imageAttachmentName":"wallet-test-face","displayMode":"ALL","futureLayout":"v9"}
        }"""))

    @Test fun notesOnlyEditPreservesEveryOriginalFieldAndFutureCustomType() {
        val source = snapshot()
        val original = source.encode()
        val editor = EmbeddedDocumentEditorData(source)
        assertEquals(2, editor.customFields.size)
        assertEquals("张伟", editor.data.fullName)
        assertEquals("Wei", editor.data.firstName)
        val result = editor.edited(source.title, "changed", true, editor.data, editor.customFields)
        assertEquals(source.itemData, result.itemData)
        assertEquals("changed", result.notes)
        assertEquals(original, source.encode())
    }

    @Test fun clearsKnownValuesAndCardFaceWithoutDiscardingFutureFields() {
        val source = snapshot()
        val editor = EmbeddedDocumentEditorData(source)
        val result = editor.edited(source.title, source.notes, false,
            editor.data.copy(nationality = "", address3 = "", cardFace = null), editor.customFields)
        assertEquals(JsonPrimitive(""), result.itemData["nationality"])
        assertEquals(JsonPrimitive(""), result.itemData["address3"])
        assertEquals(JsonNull, result.itemData["cardFace"])
        assertEquals(source.itemData["future"], result.itemData["future"])
        assertEquals(source.itemData["customFields"], result.itemData["customFields"])
        assertFalse(result.displayItem().isFavorite)
    }

    @Test fun renamingHiddenFieldRetainsItsProtectionAndUnknownMetadata() {
        val source = snapshot()
        val editor = EmbeddedDocumentEditorData(source)
        val fields = editor.customFields.mapIndexed { index, field -> if (index == 0) field.copy(title = "Renamed") else field }
        val result = editor.edited(source.title, source.notes, true, editor.data, fields)
        val actual = result.itemData.getValue("customFields").jsonArray
        assertEquals(3, actual.size)
        assertEquals("Renamed", actual[0].jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("HIDDEN", actual[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(source.itemData.getValue("customFields").jsonArray[0].jsonObject["futurePolicy"], actual[0].jsonObject["futurePolicy"])
        assertEquals(source.itemData.getValue("customFields").jsonArray.last(), actual.last())
    }

    @Test fun deletingKnownCustomFieldsRetainsUnsupportedDataAndOriginalSnapshot() {
        val source = snapshot()
        val original = source.encode()
        val editor = EmbeddedDocumentEditorData(source)
        val result = editor.edited(source.title, source.notes, true, editor.data, emptyList())
        assertEquals(listOf(source.itemData.getValue("customFields").jsonArray.last()),
            result.itemData.getValue("customFields").jsonArray.toList())
        assertEquals(original, source.encode())
    }

    @Test fun opaqueCustomFieldContainerSurvivesEditsAndBlocksReplacement() {
        val source = snapshot().edited("Document", "original", buildJsonObject {
            put("customFields", buildJsonObject { put("futureEncryptedFields", "opaque-payload") })
        })
        val original = source.encode()
        val editor = EmbeddedDocumentEditorData(source)
        val notesOnly = editor.edited(source.title, "new notes", true, editor.data, editor.customFields)
        assertEquals(source.itemData, notesOnly.itemData)
        assertTrue(runCatching { editor.edited(source.title, "new notes", true, editor.data,
            listOf(takagi.ru.monica.data.CustomFieldDraft(title = "New", value = "value"))) }.isFailure)
        assertEquals(original, source.encode())
    }
}
