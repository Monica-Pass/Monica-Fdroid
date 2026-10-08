package takagi.ru.monica.data

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.model.*
import java.util.Locale

class CommonFieldSuggestionsTest {
    @Test fun blankExactAndMissingQueriesDoNotOfferAnything() {
        val index = CommonFieldSuggestionIndex(listOf("", " Example Bank "), CommonSuggestionField.BANK_NAME)
        for (query in listOf("", "   ", "Example Bank", "unknown")) assertTrue(index.match(query).isEmpty())
    }

    @Test fun prefixesRankBeforeFrequentSubstringMatchesAndResultsAreBounded() {
        val index = CommonFieldSuggestionIndex(List(50) { "The Example Bank" } +
            listOf("Example North", "Example West", "Example South", "Example East"), CommonSuggestionField.BANK_NAME)
        val values = index.match("example").map { it.value }
        assertEquals(3, values.size)
        assertTrue(values.all { it.startsWith("Example") })
        assertTrue(index.match("example", 0).isEmpty())
    }

    @Test fun trimAndDeduplicateWithoutChangingSavedSpellingOrNumericCodes() {
        val index = CommonFieldSuggestionIndex(listOf("  Example Bank ", "example bank", "00123"), CommonSuggestionField.BRANCH_CODE)
        assertEquals(CommonFieldCandidate("Example Bank", 2), index.match("exam").single())
        assertEquals("00123", index.match("001").single().value)
    }

    @Test fun frequencyBreaksTiesAndMatchingSupportsNonLatinText() {
        val index = CommonFieldSuggestionIndex(listOf("示例分行", "示例银行", "示例银行"), CommonSuggestionField.BANK_NAME)
        assertEquals(listOf("示例银行", "示例分行"), index.match("示例").map { it.value })
    }

    @Test fun urlPathsKeepCaseAndQueryStrings() {
        val index = CommonFieldSuggestionIndex(listOf("https://example.invalid/Account?x=1", "https://example.invalid/account?x=1"), CommonSuggestionField.WEBSITE)
        assertEquals(2, index.match("EXAMPLE.INVALID").size)
        assertTrue(index.match("EXAMPLE.INVALID").all { it.value.endsWith("?x=1") })
    }

    @Test fun normalizationIsIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val index = CommonFieldSuggestionIndex(listOf("ISSUER", "issuer"), CommonSuggestionField.ISSUED_BY)
            assertEquals(2, index.match("iss").single().uses)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun onlyExplicitlyAllowedSupplementalFieldsAreSuggested() {
        for (key in listOf("pin", "password", "cardNumber", "documentNumber", "iban", "accountNumber", "ssn", "unknown")) {
            assertNull(CommonSuggestionField.forSupplementalKey(key))
        }
        assertEquals(CommonSuggestionField.BRANCH_CODE, CommonSuggestionField.forSupplementalKey("branchCode"))
    }

    @Test fun inaccessibleAndAmbiguousOwnersAreRejected() {
        val sources = setOf("local", "keepass:2", "bitwarden:3", "mdbx:4")
        assertTrue(commonSuggestionSourceAccessible(null, null, null, sources))
        assertTrue(commonSuggestionSourceAccessible(2, null, null, sources))
        assertTrue(commonSuggestionSourceAccessible(null, 3, null, sources))
        assertTrue(commonSuggestionSourceAccessible(null, null, 4, sources))
        assertFalse(commonSuggestionSourceAccessible(null, 9, null, sources))
        assertFalse(commonSuggestionSourceAccessible(2, 3, null, sources))
        assertFalse(commonSuggestionSourceAccessible(null, null, null, emptySet()))
    }

    @Test fun damagedOrWrongMetadataCannotLeakIntoSuggestions() {
        assertNull(CommonSuggestionField.CREDENTIAL_LABEL.metadataValue(ProjectCredentialGroup.FIELD, "broken"))
        assertNull(CommonSuggestionField.BANK_NAME.metadataValue("password", "secret"))
        assertNull(CommonSuggestionField.ISSUED_BY.walletValue("broken"))
        val metadata = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(label = "Work"))).first().metadata
        assertEquals("Work", CommonSuggestionField.CREDENTIAL_LABEL.metadataValue(ProjectCredentialGroup.FIELD, metadata.raw.toString()))
    }

    @Test fun fullNamesJoinCardDocumentAndBillingWithoutInventingNameParts() {
        val card = CardWalletDataCodec.encodeBankCardData(BankCardData("secret-number", "林晓", "01", "2030"))
        val doc = CardWalletDataCodec.encodeDocumentData(DocumentData(DocumentType.PASSPORT, "secret-id", "Lin Xiao",
            firstName = "Xiao", middleName = "Mei", lastName = "Lin"))
        val address = CardWalletDataCodec.encodeBillingAddressData(BillingAddressData(fullName = "林晓"))
        assertEquals("林晓", CommonSuggestionField.FULL_NAME.walletValue(card, ItemType.BANK_CARD))
        assertEquals("Lin Xiao", CommonSuggestionField.FULL_NAME.walletValue(doc, ItemType.DOCUMENT))
        assertEquals("林晓", CommonSuggestionField.FULL_NAME.walletValue(address, ItemType.BILLING_ADDRESS))
        assertNull(CommonSuggestionField.FIRST_NAME.walletValue(card, ItemType.BANK_CARD))
        assertEquals("Xiao", CommonSuggestionField.FIRST_NAME.walletValue(doc, ItemType.DOCUMENT))
        assertEquals("Mei", CommonSuggestionField.MIDDLE_NAME.walletValue(doc, ItemType.DOCUMENT))
        assertEquals("Lin", CommonSuggestionField.LAST_NAME.walletValue(doc, ItemType.DOCUMENT))
    }

    @Test fun addressPartsStaySeparateAndPreservePostalCodeFormatting() {
        val billing = BillingAddress("示例路18号", "2-301", "示例市", "示例省", "00123", "中国")
        val card = CardWalletDataCodec.encodeBankCardData(BankCardData("secret", "Name", "01", "2030",
            billingAddress = CardWalletDataCodec.encodeBillingAddress(billing)))
        assertEquals("示例路18号", CommonSuggestionField.STREET.walletValue(card))
        assertEquals("2-301", CommonSuggestionField.APARTMENT.walletValue(card))
        assertEquals("00123", CommonSuggestionField.POSTAL_CODE.walletValue(card))
        assertEquals("示例市", CommonSuggestionField.CITY.walletValue(card))
        assertEquals("示例省", CommonSuggestionField.REGION.walletValue(card))
        assertEquals("中国", CommonSuggestionField.COUNTRY.walletValue(card))
        assertNull(CommonSuggestionField.ADDRESS_LINE_3.walletValue(card, ItemType.BANK_CARD))
    }

    @Test fun embeddedKindsMustMatchTheirMetadataTitle() {
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BILLING_ADDRESS, title = "Billing",
            itemData = CardWalletDataCodec.encodeBillingAddressData(BillingAddressData(fullName = "Example Name", city = "Example City"))))
        val title = EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.ADDRESS)
        assertEquals("Example Name", CommonSuggestionField.FULL_NAME.metadataValue(title, snapshot.encode()))
        assertEquals("Example City", CommonSuggestionField.CITY.metadataValue(title, snapshot.encode()))
        assertNull(CommonSuggestionField.FULL_NAME.metadataValue(EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.DOCUMENT), snapshot.encode()))
        assertNull(CommonSuggestionField.FULL_NAME.metadataValue("password", "Example secret"))
    }

    @Test fun legacyContactAndAddressNamesShareOnlyFullNameSuggestions() {
        for (section in listOf("CONTACT", "ADDRESS")) {
            val title = EntryContentFields.key(section, "fullName")
            assertEquals("Example Name", CommonSuggestionField.FULL_NAME.metadataValue(title, "Example Name"))
            assertNull(CommonSuggestionField.FIRST_NAME.metadataValue(title, "Example Name"))
        }
        assertEquals(CommonSuggestionField.FULL_NAME, CommonSuggestionField.forSupplementalKey("fullName"))
        assertEquals(CommonSuggestionField.APARTMENT, CommonSuggestionField.forSupplementalKey("apartment"))
    }
}
