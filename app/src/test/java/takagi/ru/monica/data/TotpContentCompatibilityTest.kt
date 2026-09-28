package takagi.ru.monica.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.model.*
import takagi.ru.monica.util.TotpDataResolver

class TotpContentCompatibilityTest {
    @Test fun editingTheCardholderPreservesBooleanHiddenAndTextFieldTypes() {
        val fields = listOf(
            SecureCustomField("Enabled", "true", SecureCustomFieldType.BOOLEAN),
            SecureCustomField("Recovery", "synthetic-secret", SecureCustomFieldType.HIDDEN),
            SecureCustomField("Contact", "help@example.invalid", SecureCustomFieldType.TEXT),
        )
        val source = BankCardData(cardNumber = "4242424242424242", cardholderName = "Before",
            expiryMonth = "09", expiryYear = "2030", customFields = fields)
        val drafts = CardWalletDataCodec.customFieldsToDrafts(source.customFields)
        val edited = source.copy(cardholderName = "After", customFields = CardWalletDataCodec.draftsToCustomFields(drafts))
        assertEquals(fields, edited.customFields)
        val reopened = CardWalletDataCodec.parseBankCardData(CardWalletDataCodec.encodeBankCardData(edited))
        assertEquals(fields, requireNotNull(reopened).customFields)
    }

    @Test fun deletingAndReorderingFieldsRetainsTheirOwnTypes() {
        val original = listOf(
            SecureCustomField("Same label", "true", SecureCustomFieldType.BOOLEAN),
            SecureCustomField("Same label", "synthetic-secret", SecureCustomFieldType.HIDDEN),
            SecureCustomField("Remove me", "text", SecureCustomFieldType.TEXT),
        )
        val drafts = CardWalletDataCodec.customFieldsToDrafts(original)
        val reordered = listOf(drafts[1].copy(title = "Renamed"), drafts[0])
        assertEquals(listOf(original[1].copy(label = "Renamed"), original[0]),
            CardWalletDataCodec.draftsToCustomFields(reordered))
    }

    @Test fun legacyAuthenticatorRemainsReadableWithoutExtraFields() {
        val old = Json.decodeFromString<TotpData>("""{"secret":"JBSWY3DPEHPK3PXP","issuer":"Example"}""")
        assertEquals("JBSWY3DPEHPK3PXP", old.secret)
        assertEquals("Example", old.issuer)
        assertEquals(30, old.period)
    }

    @Test fun mixedFieldsSurviveSerializationNormalizationAndCoreEdit() {
        val fields = listOf(
            SecureCustomField(label = "Recovery", value = "synthetic-secret", type = SecureCustomFieldType.HIDDEN),
            SecureCustomField(label = "Support", value = "help@example.invalid"),
            SecureCustomField(label = "说明", value = "第一行\n第二行 🔐"),
        )
        val source = NoteData(content = "Original note", customFields = fields)
        val read = Json.decodeFromString<NoteData>(Json.encodeToString(source))
        val edited = read.copy(content = "Edited note")
        assertEquals(fields, edited.customFields)
        val drafts = CardWalletDataCodec.customFieldsToDrafts(edited.customFields)
        assertEquals(fields, CardWalletDataCodec.draftsToCustomFields(drafts))
        assertTrue(drafts.first().isProtected)
    }
    @Test fun existingAuthenticatorMetadataSurvivesNormalizationAndCoreEdit() {
        val source = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = "Before",
            steamDeviceId = "synthetic-device", steamRevocationCode = "synthetic-revocation",
            steamRawJson = "{\"futureSteamData\":true}")
        val edited = TotpDataResolver.normalizeTotpData(source).copy(issuer = "After")
        val reopened = Json.decodeFromString<TotpData>(Json.encodeToString(edited))
        assertEquals(source.steamDeviceId, reopened.steamDeviceId)
        assertEquals(source.steamRevocationCode, reopened.steamRevocationCode)
        assertEquals(source.steamRawJson, reopened.steamRawJson)
    }

    @Test fun sharedPaymentFormatPreservesBothYearWidthsAndNormalizesPastedValues() {
        val format = takagi.ru.monica.ui.components.EntryPaymentFormat
        assertEquals("4242424242424242", format.cardNumber("4242 4242-4242 4242"))
        for (value in listOf("09/30", "09/2030", "09", "")) {
            val (month, year) = format.splitExpiry(value)
            assertEquals(value, format.joinExpiry(month, year))
            assertEquals(value, format.expiry(value))
        }
        assertEquals("09/2030", format.expiry("092030"))
        assertEquals("1234", format.cvv("12 34"))
    }
    @Test fun portableBackupKeepsCustomFieldsAndUnknownMetadata() {
        val payload = """{"secret":"JBSWY3DPEHPK3PXP","future":{"version":3},"customFields":[{"label":"Recovery","value":"secret","type":"HIDDEN"}]}"""
        val result = takagi.ru.monica.utils.PortableTotpBackupCodec.encode(payload, "Example") { it }
        assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(result))
    }
}
