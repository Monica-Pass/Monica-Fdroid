package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem

/** Read-only projection of legacy fields. Persist only when the user saves the embedded editor. */
object PasswordWalletProjection {
    fun address(entry: PasswordEntry, fields: List<CustomFieldDraft>, title: String): EmbeddedWalletContent.Snapshot {
        val saved = EmbeddedWalletContent.read(fields.firstOrNull {
            it.title == EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.ADDRESS)
        }?.value)
        require(saved !is EmbeddedWalletContent.ReadResult.Unavailable) { "Unsupported address must be preserved" }
        if (saved is EmbeddedWalletContent.ReadResult.Available) return saved.snapshot
        fun extra(section: String, key: String) = EntryContentFields.value(fields, section, key)
        val fullName = extra("ADDRESS", "fullName").ifBlank { extra("CONTACT", "fullName") }
        val company = extra("ADDRESS", "company").ifBlank { extra("CONTACT", "company") }
        val data = BillingAddressData(fullName = fullName, company = company,
            streetAddress = entry.addressLine, apartment = extra("ADDRESS", "apartment"),
            city = entry.city, stateProvince = entry.state, postalCode = entry.zipCode, country = entry.country,
            email = entry.email.ifBlank { extra("ADDRESS", "email") },
            phone = entry.phone.ifBlank { extra("ADDRESS", "phone") })
        val canonical = mapOf("fullName" to fullName, "company" to company, "apartment" to data.apartment,
            "email" to data.email, "phone" to data.phone)
        // Conflicting values and document-only fields remain visible/editable, as well as retaining
        // their original encrypted custom fields. Do not collapse by label or discard unknown keys.
        val extras = fields.filter { field ->
            (field.title.startsWith("monica.content.contact.") || field.title.startsWith("monica.content.address.")) &&
                field.value.isNotEmpty() && canonical[field.title.substringAfterLast('.')] != field.value
        }.map { it.copy(title = it.title.removePrefix("monica.content.")) }
        return EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BILLING_ADDRESS, title = title,
            itemData = CardWalletDataCodec.encodeBillingAddressData(data.copy(customFields = CardWalletDataCodec.draftsToCustomFields(extras)))))
    }

    fun payment(entry: PasswordEntry, fields: List<CustomFieldDraft>, title: String): EmbeddedWalletContent.Snapshot {
        val (month, year) = entry.creditCardExpiry.split('/').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val data = BankCardData(cardNumber = entry.creditCardNumber, cardholderName = entry.creditCardHolder,
            expiryMonth = month, expiryYear = year, cvv = entry.creditCardCVV)
        val raw = Json.parseToJsonElement(CardWalletDataCodec.encodeBankCardData(data)).jsonObject
        val extras = fields.filter { it.title.startsWith("monica.content.payment.") }
            .associate { it.title.substringAfterLast('.') to JsonPrimitive(it.value) }
        return EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = title,
            itemData = JsonObject(raw + extras).toString()))
    }

    fun walletOrder(order: List<String>): List<String> = order.map { if (it == "CONTACT") "ADDRESS" else it }.distinct()
}
