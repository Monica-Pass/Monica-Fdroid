package takagi.ru.monica.data.model

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*

class PasswordWalletProjectionTest {
    private val entry = PasswordEntry(title = "login", website = "", username = "user", password = "pw",
        email = "one@example.org|two@example.org", phone = "123|456", addressLine = "street", city = "city", country = "country")
    @Test fun mergesContactAndAddressWithoutLosingConflictsOrDocumentFields() {
        val fields = listOf(
            CustomFieldDraft(title = EntryContentFields.key("CONTACT", "fullName"), value = "contact name"),
            CustomFieldDraft(title = EntryContentFields.key("ADDRESS", "fullName"), value = "billing name"),
            CustomFieldDraft(title = EntryContentFields.key("CONTACT", "passportNumber"), value = "secret id", isProtected = true),
            CustomFieldDraft(title = EntryContentFields.key("CONTACT", "future"), value = "unknown"))
        val snapshot = PasswordWalletProjection.address(entry, fields, "Address")
        val data = requireNotNull(CardWalletDataCodec.parseBillingAddressData(snapshot.itemData.toString()))
        assertEquals("billing name", data.fullName)
        assertEquals(entry.email, data.email); assertEquals(entry.phone, data.phone)
        val extras = CardWalletDataCodec.customFieldsToDrafts(data.customFields)
        assertTrue(extras.any { it.value == "contact name" })
        assertTrue(extras.any { it.value == "secret id" && it.isProtected })
        assertTrue(extras.any { it.value == "unknown" })
        assertEquals(4, fields.size)
    }

    @Test fun eachLegacySectionWorksIndependently() {
        val personal = PasswordWalletProjection.address(entry.copy(addressLine = "", city = "", country = ""), emptyList(), "Address")
        assertEquals(entry.email, CardWalletDataCodec.parseBillingAddressData(personal.itemData.toString())!!.email)
        val address = PasswordWalletProjection.address(entry.copy(email = "", phone = ""), emptyList(), "Address")
        assertEquals(entry.addressLine, CardWalletDataCodec.parseBillingAddressData(address.itemData.toString())!!.streetAddress)
    }

    @Test fun existingSnapshotAndAssetsTakePrecedenceOverLegacyProjection() {
        val source = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BILLING_ADDRESS, title = "Independent",
            itemData = CardWalletDataCodec.encodeBillingAddressData(BillingAddressData(fullName = "name"))))
            .withAssets(listOf(EmbeddedWalletContent.Asset("wallet-123", "face.jpg", "image/jpeg",
                EmbeddedWalletContent.AssetRole.CARD_FACE, 123, "0".repeat(64))))
        val fields = EmbeddedWalletContent.put(emptyList(), source)
        assertEquals(source.encode(), PasswordWalletProjection.address(entry, fields, "Address").encode())
    }

    @Test(expected = IllegalArgumentException::class) fun unknownSnapshotCannotBeReplaced() {
        PasswordWalletProjection.address(entry, listOf(CustomFieldDraft(title = EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.ADDRESS), value = "future schema")), "Address")
    }

    @Test fun mergedOrderKeepsFirstPositionAndOtherContentOrder() {
        assertEquals(listOf("NOTES", "ADDRESS", "BLOCK:a", "PAYMENT"),
            PasswordWalletProjection.walletOrder(listOf("NOTES", "CONTACT", "BLOCK:a", "ADDRESS", "PAYMENT")))
    }

    @Test fun legacyCardKeepsExtraBankFields() {
        val source = entry.copy(creditCardNumber = "1234", creditCardHolder = "Name", creditCardExpiry = "03/2032", creditCardCVV = "001")
        val fields = listOf(CustomFieldDraft(title = EntryContentFields.key("PAYMENT", "iban"), value = "DE123", isProtected = true))
        val snapshot = PasswordWalletProjection.payment(source, fields, "Card")
        val card = requireNotNull(CardWalletDataCodec.parseBankCardData(snapshot.itemData.toString()))
        assertEquals("1234", card.cardNumber); assertEquals("001", card.cvv)
        assertEquals("03", card.expiryMonth); assertEquals("2032", card.expiryYear)
        assertEquals("DE123", card.iban)
    }
}
