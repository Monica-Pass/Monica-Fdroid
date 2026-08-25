package takagi.ru.monica.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry

class PasswordCredentialSavePlannerTest {

    @Test
    fun editingSingleCredentialUpdatesTheExistingEntryAndCreatesOnlyAdditions() {
        val credentials = listOf(
            PasswordCredentialDraft(
                username = "existing@example.com",
                password = "updated-secret",
                authenticatorKey = "existing-totp"
            ),
            PasswordCredentialDraft(
                username = "new@example.com",
                password = "new-secret",
                authenticatorKey = "new-totp"
            )
        )

        val plan = buildEditedPasswordCredentialSavePlan(
            originalIds = listOf(42L),
            credentials = credentials
        )

        requireNotNull(plan)
        assertEquals(credentials.first(), plan.existingCredential)
        assertEquals(credentials.drop(1), plan.newCredentials)
    }

    @Test
    fun legacyGroupedPasswordEditIsNotConvertedIntoIndependentCredentials() {
        val plan = buildEditedPasswordCredentialSavePlan(
            originalIds = listOf(42L, 43L),
            credentials = listOf(
                PasswordCredentialDraft("account", "first-secret"),
                PasswordCredentialDraft("account", "second-secret")
            )
        )

        assertNull(plan)
    }

    @Test
    fun editWithoutAnAddedCredentialKeepsTheNormalSingleEntrySavePath() {
        val plan = buildEditedPasswordCredentialSavePlan(
            originalIds = listOf(42L),
            credentials = listOf(PasswordCredentialDraft("account", "updated-secret"))
        )

        assertNull(plan)
    }

    @Test
    fun identicalCredentialsStillReceiveIndependentFreshIdentities() {
        val source = PasswordEntry(
            id = 42,
            title = "Example",
            website = "https://example.com",
            username = "old-account",
            password = "old-password",
            notes = "shared notes",
            keepassDatabaseId = 7,
            keepassEntryUuid = "old-keepass-entry",
            keepassGroupUuid = "old-keepass-group",
            bitwardenCipherId = "old-cipher",
            bitwardenRevisionDate = "old-revision",
            bitwardenLocalModified = true,
            replicaGroupId = "old-replica-group"
        )
        val replicaIds = ArrayDeque(listOf("password:credential-a", "password:credential-b"))

        val templates = buildIndependentPasswordCredentialTemplates(
            commonEntry = source,
            credentials = listOf(
                PasswordCredentialDraft(username = "same", password = "same-secret"),
                PasswordCredentialDraft(username = "same", password = "same-secret")
            ),
            replicaGroupIdFactory = { replicaIds.removeFirst() }
        )

        assertEquals(2, templates.size)
        assertEquals(0L, templates[0].id)
        assertEquals(0L, templates[1].id)
        assertEquals("same", templates[0].username)
        assertEquals("same-secret", templates[0].password)
        assertEquals("same", templates[1].username)
        assertEquals("same-secret", templates[1].password)
        assertNotEquals(templates[0].replicaGroupId, templates[1].replicaGroupId)
        assertEquals("password:credential-a", templates[0].replicaGroupId)
        assertEquals("password:credential-b", templates[1].replicaGroupId)
        templates.forEach { template ->
            assertNull(template.keepassEntryUuid)
            assertNull(template.keepassGroupUuid)
            assertNull(template.bitwardenCipherId)
            assertNull(template.bitwardenRevisionDate)
            assertTrue(!template.bitwardenLocalModified)
        }
    }

    @Test
    fun commonCoreMetadataIsCopiedWhileNotesAndAuthenticatorRemainCredentialScoped() {
        val common = PasswordEntry(
            title = "Shared title",
            website = "https://example.com/login",
            username = "",
            password = "",
            notes = "Shared note",
            appPackageName = "com.example.app",
            appName = "Example",
            categoryId = 9,
            authenticatorKey = "must-not-leak-to-all",
            passkeyBindings = "must-not-leak-to-all",
            sshKeyData = "must-not-leak-to-all",
            customIconType = "UPLOADED",
            customIconValue = "shared.png"
        )

        val templates = buildIndependentPasswordCredentialTemplates(
            commonEntry = common,
            credentials = listOf(
                PasswordCredentialDraft(
                    username = "first@example.com",
                    password = "first-secret",
                    authenticatorKey = "totp-for-first",
                    passkeyBindings = "first-passkey",
                    sshKeyData = "first-ssh",
                    customIconValue = "first.png"
                ),
                PasswordCredentialDraft(
                    username = "second@example.com",
                    password = "second-secret",
                    authenticatorKey = "",
                    customIconValue = "second.png"
                )
            ),
            replicaGroupIdFactory = { java.util.UUID.randomUUID().toString() }
        )

        assertEquals(listOf("Shared title", "Shared title"), templates.map { it.title })
        assertEquals(listOf("", ""), templates.map { it.notes })
        assertEquals(listOf("com.example.app", "com.example.app"), templates.map { it.appPackageName })
        assertEquals(listOf("totp-for-first", ""), templates.map { it.authenticatorKey })
        assertEquals(listOf("first-passkey", ""), templates.map { it.passkeyBindings })
        assertEquals(listOf("first-ssh", ""), templates.map { it.sshKeyData })
        assertEquals(listOf("first.png", "second.png"), templates.map { it.customIconValue })
    }

    @Test
    fun notesPersonalAddressAndPaymentDataRemainCredentialScoped() {
        val templates = buildIndependentPasswordCredentialTemplates(
            commonEntry = PasswordEntry(
                title = "Shared title",
                website = "https://example.com",
                username = "",
                password = "",
                notes = "must-not-leak",
                email = "common@example.com",
                phone = "10000",
                addressLine = "common address",
                creditCardNumber = "1111"
            ),
            credentials = listOf(
                PasswordCredentialDraft(
                    username = "first",
                    password = "first-secret",
                    notes = "first note",
                    boundNoteId = 11L,
                    email = "first@example.com",
                    phone = "111111",
                    addressLine = "first address",
                    city = "first city",
                    state = "first state",
                    zipCode = "100001",
                    country = "first country",
                    creditCardNumber = "4111111111111111",
                    creditCardHolder = "First Holder",
                    creditCardExpiry = "01/30",
                    creditCardCVV = "123"
                ),
                PasswordCredentialDraft(
                    username = "second",
                    password = "second-secret",
                    notes = "second note",
                    boundNoteId = 22L,
                    email = "second@example.com",
                    phone = "222222",
                    addressLine = "second address",
                    city = "second city",
                    state = "second state",
                    zipCode = "200002",
                    country = "second country",
                    creditCardNumber = "5555555555554444",
                    creditCardHolder = "Second Holder",
                    creditCardExpiry = "02/31",
                    creditCardCVV = "456"
                )
            )
        )

        assertEquals(listOf("first note", "second note"), templates.map { it.notes })
        assertEquals(listOf(11L, 22L), templates.map { it.boundNoteId })
        assertEquals(listOf("first@example.com", "second@example.com"), templates.map { it.email })
        assertEquals(listOf("111111", "222222"), templates.map { it.phone })
        assertEquals(listOf("first address", "second address"), templates.map { it.addressLine })
        assertEquals(listOf("first city", "second city"), templates.map { it.city })
        assertEquals(listOf("first state", "second state"), templates.map { it.state })
        assertEquals(listOf("100001", "200002"), templates.map { it.zipCode })
        assertEquals(listOf("first country", "second country"), templates.map { it.country })
        assertEquals(listOf("4111111111111111", "5555555555554444"), templates.map { it.creditCardNumber })
        assertEquals(listOf("First Holder", "Second Holder"), templates.map { it.creditCardHolder })
        assertEquals(listOf("01/30", "02/31"), templates.map { it.creditCardExpiry })
        assertEquals(listOf("123", "456"), templates.map { it.creditCardCVV })
    }

    @Test
    fun credentialCustomFieldsExtendCommonFieldsAndOverrideSameNamedFields() {
        val commonFields = listOf(
            CustomFieldDraft(title = "Environment", value = "Production"),
            CustomFieldDraft(title = "Shared", value = "common value")
        )
        val credentialFields = listOf(
            CustomFieldDraft(title = "Shared", value = "credential value"),
            CustomFieldDraft(title = "Recovery", value = "credential only")
        )

        val merged = mergePasswordCredentialCustomFields(commonFields, credentialFields)

        assertEquals(listOf("Environment", "Shared", "Recovery"), merged.map { it.title })
        assertEquals(listOf("Production", "credential value", "credential only"), merged.map { it.value })
    }

    @Test
    fun identicalIndependentCredentialTemplatesRemainVisibleAsTwoItems() {
        val templates = buildIndependentPasswordCredentialTemplates(
            commonEntry = PasswordEntry(
                title = "Same",
                website = "example.com",
                username = "",
                password = ""
            ),
            credentials = listOf(
                PasswordCredentialDraft("same", "same-secret"),
                PasswordCredentialDraft("same", "same-secret")
            ),
            replicaGroupIdFactory = ArrayDeque(
                listOf("password:first", "password:second")
            )::removeFirst
        ).mapIndexed { index, entry -> entry.copy(id = (index + 1).toLong()) }

        val visible = dedupePasswordDisplayRows(
            templates.map { it to "same-secret" }
        ) { candidates -> candidates.firstOrNull() }

        assertEquals(2, visible.size)
    }
}
