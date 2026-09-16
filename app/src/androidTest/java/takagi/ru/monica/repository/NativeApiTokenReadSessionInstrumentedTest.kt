package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.MdbxWriteCommand

@RunWith(AndroidJUnit4::class)
class NativeApiTokenReadSessionInstrumentedTest {
    @Test fun ordinaryFolderTagAndIndexReadsPreserveTheNativeUnlock() = runBlocking {
        val fixture = Fixture()
        try {
            val id = fixture.create()
            val folder = fixture.repository.createFolder(id, "Browse folder", null)
            val token = fixture.repository.saveNativeApiToken(id, null, "Synthetic token", PAYLOAD,
                collectionId = folder.folderId)
            fixture.repository.listNativeApiTokens(id)
            val originalSession = fixture.sessionId(id)
            val otherRepository = Mdbx2Repository(fixture.context, fixture.dao, fixture.security)
            assertEquals(1, otherRepository.listFolders(id).size)
            assertTrue(otherRepository.listProjectTags(id, folder.folderId).isEmpty())
            assertTrue(otherRepository.listAllProjectTags(id).isEmpty())
            assertEquals(1, otherRepository.searchProjects(id, "Browse", emptyList()).size)
            assertNotNull(otherRepository.getCurrentHeadCommitId(id))
            assertTrue(otherRepository.readStoredEntries(id).any { it.entryId == token.entryId })
            val detail = fixture.repository.readNativeApiToken(id, token.entryId)
            assertEquals(ApiTokenPayload.decode(PAYLOAD), ApiTokenPayload.decode(detail.payload))
            assertEquals("Browse folder", detail.summary.collectionTitle)
            assertEquals(originalSession, fixture.sessionId(id))
        } finally { fixture.close() }
    }

    @Test fun listAndDetailReuseTheNativeSessionAndLockForcesANewUnlock() = runBlocking {
        val fixture = Fixture()
        try {
            val id = fixture.create()
            val token = fixture.repository.saveNativeApiToken(id, null, "Synthetic token", PAYLOAD)
            fixture.repository.listNativeApiTokens(id)
            val firstSession = fixture.sessionId(id)
            fixture.repository.readNativeApiToken(id, token.entryId)
            assertEquals(firstSession, fixture.sessionId(id))
            SessionManager.markLocked()
            SessionManager.markUnlocked()
            assertNotEquals(firstSession, fixture.sessionId(id))
            Mdbx2NativeReadSessions.updateForeground(false)
            val backgroundSession = fixture.sessionId(id)
            assertNotEquals("Background reads must not retain an unlocked handle", backgroundSession, fixture.sessionId(id))
            Mdbx2NativeReadSessions.updateForeground(true)
            assertEquals(ApiTokenPayload.decode(PAYLOAD),
                ApiTokenPayload.decode(fixture.repository.readNativeApiToken(id, token.entryId).payload))
        } finally { fixture.close() }
    }

    @Test fun otherRepositoryMutationsAndCredentialChangesInvalidateWarmReaders() = runBlocking {
        val fixture = Fixture()
        try {
            val id = fixture.create()
            val summary = fixture.repository.saveNativeApiToken(id, null, "Before edit", PAYLOAD)
            val original = fixture.repository.readNativeApiToken(id, summary.entryId)
            val firstSession = fixture.sessionId(id)
            val otherRepository = Mdbx2Repository(fixture.context, fixture.dao, fixture.security)
            otherRepository.saveNativeApiToken(id, original, "After edit", PAYLOAD)
            val updated = fixture.repository.readNativeApiToken(id, summary.entryId)
            assertEquals("After edit", updated.summary.title)
            assertNotEquals(firstSession, fixture.sessionId(id))

            val database = checkNotNull(fixture.dao.getDatabaseById(id))
            fixture.dao.updateDatabase(database.copy(encryptedPassword = fixture.security.encryptData("Wrong synthetic credential")))
            assertTrue("A retained reader must not bypass a changed unlock credential", runCatching {
                fixture.repository.readNativeApiToken(id, summary.entryId)
            }.isFailure)
            fixture.dao.updateDatabase(database)
            fixture.repository.readNativeApiToken(id, summary.entryId)
            otherRepository.deleteNativeApiToken(updated)
            assertTrue(fixture.repository.listNativeApiTokens(id).isEmpty())
            assertTrue(runCatching { fixture.repository.readNativeApiToken(id, summary.entryId) }.isFailure)
        } finally { fixture.close() }
    }

    @Test fun replacingTheFileAtTheSamePathCannotReturnTheOldToken() = runBlocking {
        val fixture = Fixture()
        try {
            val originalId = fixture.create()
            val replacementId = fixture.create()
            val original = fixture.repository.saveNativeApiToken(originalId, null, "Old file token", PAYLOAD)
            val replacementPayload = ApiTokenPayload.update(PAYLOAD, "token", "synthetic-replacement-token")
            fixture.repository.withVaultForSync(replacementId) { _, vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-replacement", listOf(
                    MdbxWriteCommand.CreateEntry(original.entryId,
                        Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId),
                        ApiTokenPayload.NATIVE_TYPE, "Replacement file token", replacementPayload)))
            }
            fixture.repository.listNativeApiTokens(originalId)
            val firstSession = fixture.sessionId(originalId)
            Files.move(fixture.file(replacementId).toPath(), fixture.file(originalId).toPath(),
                StandardCopyOption.REPLACE_EXISTING)
            val read = fixture.repository.readNativeApiToken(originalId, original.entryId)
            assertEquals("Replacement file token", read.summary.title)
            assertEquals(ApiTokenPayload.decode(replacementPayload), ApiTokenPayload.decode(read.payload))
            assertNotEquals(firstSession, fixture.sessionId(originalId))
        } finally { fixture.close() }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = PasswordDatabase.getDatabase(context).localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        private val executor = Mdbx2VaultSessionExecutor(context, dao, security)
        private val wasUnlocked = SessionManager.isUnlocked.value
        private val wasForeground = Mdbx2NativeReadSessions.isForeground
        private val owned = mutableMapOf<Long, File>()

        init {
            Mdbx2NativeReadSessions.updateForeground(true)
            SessionManager.markUnlocked()
        }

        suspend fun create(): Long {
            val password = "Synthetic native reader test password 123"
            val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
            return dao.insertDatabase(LocalMdbxDatabase(name = "Synthetic reader test", filePath = file.absolutePath,
                storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue)).also { owned[it] = file }
        }

        fun file(id: Long) = owned.getValue(id)
        suspend fun sessionId(id: Long) = executor.withNativeReadVault(id) { _, vault ->
            checkNotNull(vault.activeSessionInfo()).sessionId
        }

        suspend fun close() {
            Mdbx2NativeReadSessions.clear()
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
            owned.forEach { (id, file) -> dao.deleteDatabaseById(id); repository.deleteOwnedVaultFile(file) }
        }
    }

    companion object {
        private const val PAYLOAD = """{"schema":"monica.api-token.v1","provider":"Example","api_base":"","token":"synthetic-session-test-token"}"""
    }
}
