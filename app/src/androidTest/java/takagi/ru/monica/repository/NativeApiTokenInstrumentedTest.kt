package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.MdbxWriteCommand
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeApiTokenInstrumentedTest {
    @Test fun cliNativeIdentityCategoryExtensionsAndSnapshotSurviveRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        val password = "Synthetic native token vault password 123"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        try {
            databaseId = dao.insertDatabase(LocalMdbxDatabase(
                name = "Native token test", filePath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                encryptedPassword = security.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue
            ))
            val parent = repository.createFolder(databaseId, "CLI parent", null)
            val child = repository.createFolder(databaseId, "CLI child", parent.folderId)
            val entryId = UUID.randomUUID().toString()
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://gitlab.example.test/api/v4/","note":"CLI note","token":"synthetic-cli-token-123456","future":{"scope":"api"}}"""
            // Exactly the native CreateEntry contract used by Monica CLI: no Android password metadata.
            repository.withVaultForSync(databaseId) { _, vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "test-cli-create", listOf(
                    MdbxWriteCommand.CreateEntry(entryId, child.folderId, "api-token", "cli-credential", payload),
                    MdbxWriteCommand.CreateEntry(UUID.randomUUID().toString(), child.folderId, "login", "Not a token", "{}")
                ))
            }
            val summary = repository.listNativeApiTokens(databaseId).single()
            assertEquals(entryId, summary.entryId)
            assertEquals(child.folderId, summary.collectionId)
            assertFalse(summary.isFavorite)
            val original = repository.readNativeApiToken(summary)
            assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(original.payload))
            val snapshot = repository.createSnapshot(databaseId, "Native token before edit")
            val edited = ApiTokenPayload.update(original.payload, "note", "Edited on Android")
            val metadata = """{"schema":"monica.api-token.fields.v1","notes":"Private app notes\nSecond line","custom_fields":[{"id":-1,"title":"Scope","value":"synthetic-scope","protected":true,"future":42}],"extension":{"source":"test"}}"""
            repository.saveNativeApiToken(databaseId, original, "android-renamed", edited, isFavorite = true, metadata = metadata)
            val reopened = Mdbx2Repository(context, dao, security)
            val updatedSummary = reopened.listNativeApiTokens(databaseId).single()
            assertEquals(entryId, updatedSummary.entryId)
            assertEquals(child.folderId, updatedSummary.collectionId)
            assertEquals("android-renamed", updatedSummary.title)
            assertTrue(updatedSummary.isFavorite)
            assertTrue(reopened.readNativeApiToken(updatedSummary).summary.isFavorite)
            // Native label JSON is canonicalized; compare every value, not property order.
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(checkNotNull(reopened.readNativeApiToken(updatedSummary).extras).payload))
            assertEquals(ApiTokenPayload.decode(edited), ApiTokenPayload.decode(reopened.readNativeApiToken(updatedSummary).payload))
            assertTrue(runCatching {
                repository.saveNativeApiToken(databaseId, original, "stale-edit", original.payload)
            }.isFailure)
            assertTrue(repository.readStoredEntries(databaseId).any { it.entryId == entryId && it.entryType == "api-token" })
            assertTrue(room.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId).isEmpty())
            val moved = repository.saveNativeApiToken(databaseId, reopened.readNativeApiToken(updatedSummary),
                "android-renamed", edited, collectionId = "")
            assertEquals(entryId, moved.entryId)
            assertNotEquals(child.folderId, moved.collectionId)
            val beforeUnfavorite = repository.readNativeApiToken(moved)
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(checkNotNull(beforeUnfavorite.extras).payload))
            assertTrue(beforeUnfavorite.summary.isFavorite)
            repository.saveNativeApiToken(databaseId, beforeUnfavorite, moved.title, edited, isFavorite = false)
            assertFalse(repository.listNativeApiTokens(databaseId).single().isFavorite)
            val unfavorited = repository.readNativeApiToken(databaseId, entryId)
            assertFalse(unfavorited.summary.isFavorite)
            assertEquals(ApiTokenPayload.decode(edited), ApiTokenPayload.decode(unfavorited.payload))
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(checkNotNull(unfavorited.extras).payload))
            assertTrue("A stale editor must not undo a newer favorite change", runCatching {
                repository.saveNativeApiToken(databaseId, beforeUnfavorite, moved.title, edited)
            }.isFailure)
            val refavorited = repository.saveNativeApiToken(databaseId, unfavorited, moved.title, edited, isFavorite = true)
            assertTrue(repository.readNativeApiToken(refavorited).summary.isFavorite)
            repository.deleteNativeApiToken(repository.readNativeApiToken(refavorited))
            assertTrue(repository.listNativeApiTokens(databaseId).isEmpty())
            repository.revertToSnapshot(databaseId, snapshot.snapshotId)
            val restored = repository.readNativeApiToken(repository.listNativeApiTokens(databaseId).single())
            assertEquals(child.folderId, restored.summary.collectionId)
            assertFalse(restored.summary.isFavorite)
            assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(restored.payload))
            assertNull(restored.extras)
        } finally {
            if (databaseId > 0) dao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(file)
        }
    }

    @Test fun customTokenCopyAndMovePreserveNativeFieldsAndIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        val owned = mutableListOf<Pair<Long, java.io.File>>()
        suspend fun create(): Long {
            val password = "Synthetic transfer password 123"
            val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
            return dao.insertDatabase(LocalMdbxDatabase(name = "Synthetic native transfer", filePath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue)).also { owned += it to file }
        }
        try {
            val source = create()
            val destination = create()
            val folder = repository.createFolder(destination, "Work", null)
            val payload = """{"schema":"monica.api-token.v1","provider":"Custom service","api_base":"https://example.test/custom/endpoint","token":"synthetic-transfer-token","unknown":{"keep":true}}"""
            val metadata = ApiTokenMetadata.withCustomFields(ApiTokenMetadata.withNotes(ApiTokenMetadata.empty(), "notes\nwith lines"),
                listOf(CustomFieldDraft(-5, "Secret field", "synthetic secret", true)))
            val original = repository.saveNativeApiToken(source, null, "工作令牌", payload, isFavorite = true, metadata = metadata)
            val copy = repository.transferNativeApiToken(original, destination, folder.folderId, copy = true)
            assertNotEquals(original.entryId, copy.entryId)
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(checkNotNull(repository.readNativeApiToken(copy).extras).payload))
            assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(repository.readNativeApiToken(copy).payload))
            assertEquals(1, repository.listNativeApiTokens(source).size)
            val moved = repository.transferNativeApiToken(original, destination, null, copy = false)
            assertEquals(original.entryId, moved.entryId)
            assertTrue(repository.listNativeApiTokens(source).isEmpty())
            assertEquals(2, repository.listNativeApiTokens(destination).size)
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(checkNotNull(repository.readNativeApiToken(moved).extras).payload))
            assertTrue(repository.readNativeApiToken(moved).summary.isFavorite)
            assertTrue(repository.listNativeApiTokens(destination).first { it.entryId == moved.entryId }.isRootCollection)
            assertTrue(room.passwordEntryDao().getByMdbxDatabaseIdSync(destination).isEmpty())
        } finally {
            owned.forEach { (id, file) -> dao.deleteDatabaseById(id); repository.deleteOwnedVaultFile(file) }
        }
    }
}
