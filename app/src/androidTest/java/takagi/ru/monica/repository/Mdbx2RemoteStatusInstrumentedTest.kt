package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxRemoteSource
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager

@RunWith(AndroidJUnit4::class)
class Mdbx2RemoteStatusInstrumentedTest {
    @Test
    fun remoteRustMutationBecomesPendingUpload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val remoteSourceDao = room.mdbxRemoteSourceDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "remote-status-${UUID.randomUUID()}"
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val sourceId = remoteSourceDao.insertSource(
            MdbxRemoteSource(
                displayName = "status-source-${UUID.randomUUID()}",
                remotePath = "vaults/status.mdbx",
                baseUrl = "https://example.invalid",
                usernameEncrypted = securityManager.encryptData("user"),
                passwordEncrypted = securityManager.encryptData("password")
            )
        )
        val databaseId = databaseDao.insertDatabase(
            LocalMdbxDatabase(
                name = "remote-status-${UUID.randomUUID()}",
                filePath = "vaults/status.mdbx",
                storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
                sourceId = sourceId,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                tigaMode = MdbxTigaMode.SKY.name,
                encryptedPassword = securityManager.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                kdfProfile = "argon2id",
                workingCopyPath = vaultFile.absolutePath,
                cacheCopyPath = vaultFile.absolutePath,
                isOfflineAvailable = true,
                lastSyncedAt = System.currentTimeMillis(),
                lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
            )
        )
        try {
            repository.upsertPassword(
                PasswordEntry(
                    title = "pending marker",
                    website = "https://example.invalid",
                    username = "user",
                    password = "secret",
                    mdbxDatabaseId = databaseId
                )
            )
            assertEquals(
                MdbxSyncStatus.PENDING_UPLOAD.name,
                databaseDao.getDatabaseById(databaseId)?.lastSyncStatus
            )
            assertTrue(repository.getPendingSyncCount(databaseId) >= 1)
            val pendingDiagnostics = repository.getVaultDiagnostics(databaseId)
            assertTrue(pendingDiagnostics.integrityOk)
            assertTrue(pendingDiagnostics.commitCount >= 1)
            assertTrue(pendingDiagnostics.deviceCount >= 1)
            assertTrue(pendingDiagnostics.entryCount >= 1)
            assertTrue(pendingDiagnostics.pendingSyncCount >= 1)

            databaseDao.updateSyncSuccess(
                databaseId = databaseId,
                status = MdbxSyncStatus.IN_SYNC.name,
                time = System.currentTimeMillis()
            )
            assertEquals(0, repository.getPendingSyncCount(databaseId))
            assertEquals(0, repository.getVaultDiagnostics(databaseId).pendingSyncCount)
        } finally {
            room.passwordEntryDao().deleteAllByMdbxDatabaseId(databaseId)
            databaseDao.deleteDatabaseById(databaseId)
            remoteSourceDao.deleteSourceById(sourceId)
            repository.deleteOwnedVaultFile(vaultFile)
        }
    }
}
