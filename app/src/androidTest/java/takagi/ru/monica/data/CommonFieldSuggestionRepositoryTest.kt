package takagi.ru.monica.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.CommonFieldSuggestionRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.PasswordWebsiteCodec

class CommonFieldSuggestionRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
    private val security = SecurityManager(context)
    private val unlocked = MutableStateFlow(true)
    private val repository = CommonFieldSuggestionRepository(db, security::decryptDataIfMonicaCiphertext, unlocked)
    @After fun close() = db.close()

    private suspend fun values(field: CommonSuggestionField, query: String): List<String> =
        withTimeout(10000) { repository.observe(field).first().match(query).map { it.value } }
    private fun card(name: String = "Example Bank", branch: String = "00123", phone: String = "400-000-0000") =
        BankCardData("synthetic-card", "Synthetic owner", "01", "2030", bankName = name, branchCode = branch,
            customerServicePhone = phone, pin = "never-suggest-pin")

    @Test fun encryptedWalletFieldsAreSuggestedWithoutUnrelatedSecrets() = runBlocking {
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD, title = "Synthetic card",
            itemData = security.encryptData(CardWalletDataCodec.encodeBankCardData(card()))))
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.DOCUMENT, title = "Synthetic identity",
            itemData = security.encryptData(CardWalletDataCodec.encodeDocumentData(DocumentData(DocumentType.PASSPORT,
                "synthetic-identity", "Synthetic owner", issuedBy = "Example Authority")))))
        assertEquals(listOf("Example Bank"), values(CommonSuggestionField.BANK_NAME, "Exam"))
        assertEquals(listOf("00123"), values(CommonSuggestionField.BRANCH_CODE, "00"))
        assertEquals(listOf("400-000-0000"), values(CommonSuggestionField.CUSTOMER_SERVICE_PHONE, "400"))
        assertEquals(listOf("Example Authority"), values(CommonSuggestionField.ISSUED_BY, "Exam"))
        for (field in CommonSuggestionField.entries) assertTrue(values(field, "synthetic").isEmpty())
        assertTrue(values(CommonSuggestionField.BANK_NAME, "never-suggest").isEmpty())
    }

    @Test fun websitesAndCredentialLabelsExcludeDeletedArchivedAndOrphanedSources() = runBlocking {
        val entry = PasswordEntry(title = "Synthetic", username = "", password = "unreadable-secret",
            website = PasswordWebsiteCodec.encode(listOf("https://example.invalid/One", "https://example.invalid/Two")))
        val id = db.passwordEntryDao().insert(entry)
        db.passwordEntryDao().insert(entry.copy(website = "https://deleted.invalid", isDeleted = true))
        db.passwordEntryDao().insert(entry.copy(website = "https://archived.invalid", isArchived = true))
        db.passwordEntryDao().insert(entry.copy(website = "https://orphan.invalid", keepassDatabaseId = 999))
        val metadata = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(label = "Example Work"))).first().metadata
        db.customFieldDao().insertAll(listOf(CustomField(entryId = id, title = ProjectCredentialGroup.FIELD,
            value = security.encryptData(metadata.raw.toString()))))
        assertEquals(2, values(CommonSuggestionField.WEBSITE, "example").size)
        for (query in listOf("deleted", "archived", "orphan")) assertTrue(values(CommonSuggestionField.WEBSITE, query).isEmpty())
        assertEquals(listOf("Example Work"), values(CommonSuggestionField.CREDENTIAL_LABEL, "Example"))
    }

    @Test fun embeddedAndLegacySupplementalFieldsJoinTheSameSuggestions() = runBlocking {
        val id = db.passwordEntryDao().insert(PasswordEntry(title = "Embedded", username = "", password = "", website = ""))
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = "Embedded card",
            itemData = CardWalletDataCodec.encodeBankCardData(card("Embedded Bank"))))
        db.customFieldDao().insertAll(listOf(
            CustomField(entryId = id, title = EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.BANK_CARD), value = security.encryptData(snapshot.encode())),
            CustomField(entryId = id, title = EntryContentFields.key("PAYMENT", "bankName"), value = "Legacy Bank"),
            CustomField(entryId = id, title = EntryContentFields.key("CONTACT", "issuedBy"), value = "Example Office"),
            CustomField(entryId = id, title = ProjectCredentialGroup.FIELD, value = "bad metadata")))
        assertEquals(setOf("Embedded Bank", "Legacy Bank"), values(CommonSuggestionField.BANK_NAME, "Bank").toSet())
        assertEquals(listOf("00123"), values(CommonSuggestionField.BRANCH_CODE, "00"))
        assertEquals(listOf("Example Office"), values(CommonSuggestionField.ISSUED_BY, "Example"))
        assertTrue(values(CommonSuggestionField.CREDENTIAL_LABEL, "bad").isEmpty())
    }

    @Test fun activeCollectionRemovesLockedVaultsAndTracksSavedChanges() = runBlocking {
        val vault = BitwardenVault(email = "synthetic@example.invalid", isLocked = false)
        val vaultId = db.bitwardenVaultDao().insert(vault)
        val entry = PasswordEntry(title = "Remote", username = "", password = "", website = "https://remote.invalid", bitwardenVaultId = vaultId)
        val id = db.passwordEntryDao().insert(entry)
        val channel = kotlinx.coroutines.channels.Channel<CommonFieldSuggestionIndex>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch { repository.observe(CommonSuggestionField.WEBSITE).collect { channel.send(it) } }
        suspend fun awaitValues(expected: List<String>) = withTimeout(10000) {
            while (channel.receive().match("invalid").map { it.value } != expected) { }
        }
        try {
            awaitValues(listOf("https://remote.invalid"))
            db.bitwardenVaultDao().update(vault.copy(id = vaultId, isLocked = true))
            awaitValues(emptyList())
            db.bitwardenVaultDao().update(vault.copy(id = vaultId))
            awaitValues(listOf("https://remote.invalid"))
            db.passwordEntryDao().updatePasswordEntry(entry.copy(id = id, website = "https://changed.invalid"))
            awaitValues(listOf("https://changed.invalid"))
            unlocked.value = false
            awaitValues(emptyList())
        } finally { job.cancelAndJoin() }
    }
}
