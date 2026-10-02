package takagi.ru.monica.autofill_ng

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.security.SecurityManager

class WalletAutofillRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val security = SecurityManager(context)
    private fun parent(title: String = "Shopping") = PasswordEntry(title = title, website = "", username = "", password = "")
    private fun bank(number: String = "4242424242424242") = SecureItem(itemType = ItemType.BANK_CARD, title = "Visa",
        itemData = Json.encodeToString(BankCardData(number, "Test Person", "08", "2030", "123")))
    private fun address(street: String = "123 Main Street") = SecureItem(itemType = ItemType.BILLING_ADDRESS, title = "Office",
        itemData = Json.encodeToString(BillingAddressData(fullName = "Test Person", streetAddress = street,
            apartment = "Suite 5", city = "Shanghai", stateProvince = "Shanghai", postalCode = "200000", country = "CN")))

    private suspend fun save(db: PasswordDatabase, owner: Long, item: SecureItem, snapshotId: String = "fixture") : Long {
        val snapshot = EmbeddedWalletContent.create(item, snapshotId)
        return db.customFieldDao().insert(CustomField(entryId = owner,
            title = EmbeddedWalletContent.fieldName(snapshot.kind), value = security.encryptData(snapshot.encode()), isProtected = true))
    }

    @Test fun allEmbeddedKindsReadEncryptedFieldsAndNeverCreateStandaloneRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val owner = db.passwordEntryDao().insertPasswordEntry(parent())
            val card = bank(security.encryptData("4242424242424242"))
            save(db, owner, card)
            save(db, owner, address())
            save(db, owner, SecureItem(itemType = ItemType.DOCUMENT, title = "Passport",
                itemData = Json.encodeToString(DocumentData(documentType = DocumentType.PASSPORT,
                    documentNumber = security.encryptData("P-12345"), fullName = "Test Person"))))
            val before = db.customFieldDao().getAllFieldsSync()
            val repo = WalletAutofillRepository(db, security::decryptDataIfMonicaCiphertext)
            val items = repo.loadEmbeddedItems()
            assertEquals(3, items.size)
            assertEquals(3, items.map { it.id }.distinct().size)
            assertTrue(items.all { it.id < 0 && it.title.startsWith("Shopping · ") })
            assertEquals("4242424242424242", parseBankCardCandidate(items.single { it.itemType == ItemType.BANK_CARD }, security::decryptDataIfMonicaCiphertext)!!.second.cardNumber)
            assertEquals("P-12345", parseDocumentCandidate(items.single { it.itemType == ItemType.DOCUMENT }, security::decryptDataIfMonicaCiphertext)!!.second.documentNumber)
            val data = parseBillingAddressCandidate(items.single { it.itemType == ItemType.BILLING_ADDRESS })!!.second
            assertEquals("200000", mapBillingAddressAutofillValue("POSTAL_CODE", data))
            assertEquals("Shanghai", mapBillingAddressAutofillValue("ADDRESS_CITY", data))
            assertNull(mapBillingAddressAutofillValue("PASSWORD", data))
            assertEquals(before, db.customFieldDao().getAllFieldsSync())
            assertTrue(db.secureItemDao().getActiveItemsByTypeSync(ItemType.BANK_CARD).isEmpty())
        } finally { db.close() }
    }

    @Test fun latestSnapshotIsUsedButMovedDeletedArchivedOrReplacedOwnersAreRejected() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val original = parent()
            val owner = db.passwordEntryDao().insertPasswordEntry(original)
            val fieldId = save(db, owner, address())
            val repo = WalletAutofillRepository(db, security::decryptDataIfMonicaCiphertext)
            val expected = repo.loadEmbeddedItems().single()
            val field = db.customFieldDao().getFieldById(fieldId)!!
            db.customFieldDao().update(field.copy(value = security.encryptData(EmbeddedWalletContent.create(address("New Street"), "fixture").encode())))
            assertEquals("New Street", parseBillingAddressCandidate(repo.resolveCurrent(expected)!!)!!.second.streetAddress)
            listOf(original.copy(id = owner, keepassDatabaseId = 100), original.copy(id = owner, isDeleted = true),
                original.copy(id = owner, isArchived = true)).forEach { changed ->
                db.passwordEntryDao().updatePasswordEntry(changed)
                assertNull(repo.resolveCurrent(expected))
            }
            db.passwordEntryDao().updatePasswordEntry(original.copy(id = owner))
            val other = db.passwordEntryDao().insertPasswordEntry(parent("Other"))
            db.customFieldDao().update(field.copy(entryId = other))
            assertNull(repo.resolveCurrent(expected))
        } finally { db.close() }
    }

    @Test fun legacyMirrorsAreUsedOnlyWhenNoSnapshotExistsIncludingUnreadableSnapshots() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val owner = db.passwordEntryDao().insertPasswordEntry(parent().copy(creditCardNumber = security.encryptData("4111111111111111"),
                creditCardExpiry = "03/29", addressLine = "Legacy Street", zipCode = "12345"))
            val repo = WalletAutofillRepository(db, security::decryptDataIfMonicaCiphertext)
            val old = repo.loadEmbeddedItems()
            assertEquals(2, old.size)
            val card = old.single { it.itemType == ItemType.BANK_CARD }
            assertEquals("4111111111111111", parseBankCardCandidate(card)!!.second.cardNumber)
            save(db, owner, bank())
            db.customFieldDao().insert(CustomField(entryId = owner, title = "monica.content.wallet.address", value = "V2|unreadable"))
            assertNull(repo.resolveCurrent(card))
            val items = repo.loadEmbeddedItems()
            assertEquals(1, items.size)
            assertEquals("4242424242424242", parseBankCardCandidate(items.single())!!.second.cardNumber)
        } finally { db.close() }
    }

    @Test fun wrongKindsUnknownVersionsAndCorruptFieldsAreSkippedWithoutChangingThem() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val owner = db.passwordEntryDao().insertPasswordEntry(parent())
            val valid = EmbeddedWalletContent.create(bank()).encode()
            db.customFieldDao().insertAll(listOf(
                CustomField(entryId = owner, title = "monica.content.wallet.address", value = valid),
                CustomField(entryId = owner, title = "monica.content.wallet.bank_card", value = valid.replace("\"version\":1", "\"version\":999")),
                CustomField(entryId = owner, title = "monica.content.wallet.document", value = "not JSON")))
            val before = db.customFieldDao().getAllFieldsSync()
            assertTrue(WalletAutofillRepository(db, security::decryptDataIfMonicaCiphertext).loadEmbeddedItems().isEmpty())
            assertEquals(before, db.customFieldDao().getAllFieldsSync())
        } finally { db.close() }
    }

    @Test fun sourceScopeIsInheritedAndUnavailableMdbxProjectionsStayHidden() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val mdbx = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Fixture MDBX2", filePath = "fixture-only", engineType = MdbxEngineType.RUST_MDBX2.name))
            val parents = listOf(parent("MDBX2").copy(mdbxDatabaseId = mdbx, mdbxFolderId = "mdbx-folder"), parent("Local"), parent("KeePass").copy(keepassDatabaseId = 8, keepassGroupPath = "Work"),
                parent("Bitwarden").copy(bitwardenVaultId = 9, bitwardenFolderId = "folder"),
                parent("Unavailable MDBX").copy(mdbxDatabaseId = 999), parent("Conflict").copy(keepassDatabaseId = 8, bitwardenVaultId = 9))
            parents.forEach { save(db, db.passwordEntryDao().insertPasswordEntry(it), address()) }
            val items = WalletAutofillRepository(db, security::decryptDataIfMonicaCiphertext).loadEmbeddedItems()
            assertEquals(4, items.size)
            assertEquals("mdbx-folder", items.single { it.mdbxDatabaseId == mdbx }.mdbxFolderId)
            assertEquals("Work", items.single { it.keepassDatabaseId == 8L }.keepassGroupPath)
            assertEquals("folder", items.single { it.bitwardenVaultId == 9L }.bitwardenFolderId)
            assertTrue(items.none { it.mdbxDatabaseId == 999L })
        } finally { db.close() }
    }

    @Test fun largeEditorPayloadIsNotReadAndStandaloneDeletionCannotFillCachedValues() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val owner = db.passwordEntryDao().insertPasswordEntry(parent().copy(notes = "n".repeat(3 * 1024 * 1024)))
            save(db, owner, address())
            val repo = WalletAutofillRepository(db, security::decryptDataIfMonicaCiphertext)
            assertEquals(1, repo.loadEmbeddedItems().size)
            val item = address().copy(id = db.secureItemDao().insertItem(address()))
            assertNotNull(repo.resolveCurrent(item))
            db.secureItemDao().softDelete(item.id)
            assertNull(repo.resolveCurrent(item))
        } finally { db.close() }
    }

    @Test fun damagedNestedSecretsNeverBecomeCiphertextAndPlainBase64StaysIntact() {
        val data = parseBankCardCandidate(bank("V2|broken"), security::decryptDataIfMonicaCiphertext)!!.second
        assertEquals("", data.cardNumber)
        assertEquals("123", data.cvv)
        val plain = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo="
        assertEquals(plain, parseBankCardCandidate(bank(plain), security::decryptDataIfMonicaCiphertext)!!.second.cardNumber)
    }
}
