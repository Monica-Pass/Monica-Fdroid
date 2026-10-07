package takagi.ru.monica.data

import android.os.Looper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager

@RunWith(AndroidJUnit4::class)
class ClearDataUseCaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun password(title: String = "Synthetic") = PasswordEntry(
        title = title, website = "", username = "fixture", password = "synthetic-only",
    )
    private suspend fun withDatabase(block: suspend (PasswordDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    @Test fun eachSelectionKeepsOtherTypesAndUnselectedHistory(): Unit = runBlocking {
        val selections = listOf(ClearDataSelection(passwords = true), ClearDataSelection(totp = true),
            ClearDataSelection(notes = true), ClearDataSelection(documents = true),
            ClearDataSelection(bankCards = true), ClearDataSelection(generatorHistory = true))
        for (selection in selections) withDatabase { db ->
            val passwordId = db.passwordEntryDao().insertPasswordEntry(password())
            db.customFieldDao().insert(CustomField(entryId = passwordId, title = "Extra", value = "fixture"))
            val types = listOf(ItemType.TOTP, ItemType.NOTE, ItemType.DOCUMENT, ItemType.BANK_CARD,
                ItemType.BILLING_ADDRESS, ItemType.PAYMENT_ACCOUNT)
            val items = db.secureItemDao().insertItems(types.map { SecureItem(itemType = it, title = "Fixture $it", itemData = "{}") })
            var historyClears = 0
            val progress = mutableListOf<ClearDataProgress>()
            ClearDataUseCase(PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()), { historyClears++ })
                .execute(selection) { progress += it }
            assertEquals(!selection.passwords, db.passwordEntryDao().getPasswordEntryById(passwordId) != null)
            assertEquals(if (selection.passwords) 0 else 1, db.customFieldDao().getFieldsByEntryIdSync(passwordId).size)
            items.forEach { assertEquals(it.itemType !in selection.itemTypes, db.secureItemDao().getItemById(it.id) != null) }
            assertEquals(if (selection.generatorHistory) 1 else 0, historyClears)
            assertEquals(if (selection.generatorHistory) 0 else 1, progress.last().clearedEntries)
            assertEquals(ClearDataStatus.COMPLETED, progress.last().status)
        }
    }

    @Test fun archivedTrashAndUnavailableVaultsKeepTheirOriginalClearBoundary(): Unit = runBlocking {
        withDatabase { db ->
            val glitter = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Unsupported synthetic", filePath = "/synthetic-only", engineType = MdbxEngineType.RUST_MDBX2.name,
                tigaMode = MdbxTigaMode.GLITTER.name,
            ))
            val kept = db.passwordEntryDao().insertPasswordEntries(listOf(password().copy(isArchived = true),
                password().copy(isDeleted = true), password().copy(mdbxDatabaseId = glitter)))
            val activeId = db.passwordEntryDao().insertPasswordEntry(password("Active"))
            val trashId = db.secureItemDao().insertItem(SecureItem(itemType = ItemType.NOTE,
                title = "Trash", itemData = "{}", isDeleted = true))
            ClearDataUseCase(PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()), {})
                .execute(ClearDataSelection(passwords = true, notes = true)) {}
            kept.forEach { assertNotNull(db.passwordEntryDao().getPasswordEntryById(it)) }
            assertNull(db.passwordEntryDao().getPasswordEntryById(activeId))
            assertNotNull(db.secureItemDao().getItemById(trashId))
        }
    }

    @Test fun failedMirrorBatchLeavesRowsAndReportsOnlyEarlierCommittedBatches(): Unit = runBlocking {
        withDatabase { db ->
            val databaseId = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Synthetic mirror", filePath = "/synthetic-only", engineType = MdbxEngineType.RUST_MDBX2.name,
                tigaMode = MdbxTigaMode.SKY.name,
            ))
            db.passwordEntryDao().insertPasswordEntries(List(650) { password().copy(mdbxDatabaseId = databaseId) })
            var deletes = 0
            var rollbacks = 0
            val mirror = Proxy.newProxyInstance(MdbxRepository::class.java.classLoader, arrayOf(MdbxRepository::class.java)) { _, method, _ ->
                when (method.name) {
                    "deletePasswords" -> { deletes++; if (deletes == 2) throw IllegalStateException("Synthetic write failure"); Unit }
                    "upsertPasswords" -> { rollbacks++; Unit }
                    else -> error("Unexpected call: ${method.name}")
                }
            } as MdbxRepository
            val progress = mutableListOf<ClearDataProgress>()
            val result = runCatching {
                ClearDataUseCase(PasswordRepository(db.passwordEntryDao(), mdbxRepository = mirror), SecureItemRepository(db.secureItemDao()), {})
                    .execute(ClearDataSelection(passwords = true)) { progress += it }
            }
            assertTrue(result.isFailure)
            assertEquals(2, deletes)
            assertEquals(1, rollbacks)
            assertEquals(150, db.passwordEntryDao().getActiveEntries().first().size)
            assertEquals(500, progress.last().clearedEntries)
            assertFalse(progress.any { it.status == ClearDataStatus.COMPLETED })
        }
    }

    @Test fun preparationAndHistoryRunOffMainAndFailureCannotReportCompletion(): Unit = runBlocking {
        withDatabase { db ->
            val progress = mutableListOf<ClearDataProgress>()
            val operation = ClearDataUseCase(PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()), {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                throw IllegalStateException("Synthetic history failure")
            })
            val failure = withContext(Dispatchers.Main) {
                runCatching { operation.execute(ClearDataSelection(generatorHistory = true)) {
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    progress += it
                } }.exceptionOrNull()
            }
            assertNotNull(failure)
            assertEquals(ClearDataPhase.GENERATOR_HISTORY, progress.last().phase)
            assertFalse(progress.any { it.status == ClearDataStatus.COMPLETED })
        }
    }

    @Test fun selectedMdbxEntriesStayDeletedAfterReopeningVault(): Unit = runBlocking {
        withDatabase { db ->
            val security = SecurityManager(context)
            val raw = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
            val secret = "clear-fixture-${UUID.randomUUID()}"
            val file = raw.createInitializedVaultFile(MdbxTigaMode.SKY, secret)
            try {
                val id = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                    name = "Clear fixture", filePath = file.absolutePath,
                    engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = security.encryptData(secret), workingCopyPath = file.absolutePath,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name, storageLocation = MdbxStorageLocation.INTERNAL.name,
                ))
                val repository = PasswordRepository(db.passwordEntryDao(), mdbxRepository = raw)
                val items = SecureItemRepository(db.secureItemDao(), raw)
                repository.insertPasswordEntries(List(12) { password("MDBX $it").copy(mdbxDatabaseId = id) })
                items.insertItems(listOf(SecureItem(itemType = ItemType.NOTE, title = "Keep note", itemData = "{}", mdbxDatabaseId = id)))
                ClearDataUseCase(repository, items, {}).execute(ClearDataSelection(passwords = true)) {}
                val reopened = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
                val stored = reopened.readStoredEntries(id)
                assertEquals(1, stored.count { !it.deleted })
                assertEquals(12, stored.count { it.deleted })
                assertTrue(repository.getAllPasswordEntries().first().isEmpty())
                assertEquals(1, items.getAllItems().first().size)
            } finally {
                raw.deleteOwnedVaultFile(file)
            }
        }
    }
}
