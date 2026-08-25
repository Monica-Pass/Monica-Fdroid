package takagi.ru.monica.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class Mdbx2ReleaseStabilityInstrumentedTest {
    @Test
    fun activationRepairsVaultAheadRoomAfterInterruptedMirrorCommit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(
            context = context,
            databaseDao = databaseDao,
            securityManager = securityManager,
            passwordEntryDao = room.passwordEntryDao(),
            secureItemDao = room.secureItemDao(),
            customFieldDao = room.customFieldDao()
        )
        val password = "release-stability-${UUID.randomUUID()}"
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "MDBX2 crash recovery ${UUID.randomUUID()}",
                    filePath = vaultFile.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = securityManager.encryptData(password),
                    unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    workingCopyPath = vaultFile.absolutePath,
                    cacheCopyPath = vaultFile.absolutePath,
                    isOfflineAvailable = true
                )
            )
            assertTrue(databaseId > 0L)

            repository.upsertPassword(
                PasswordEntry(
                    title = "Vault-ahead recovery marker",
                    website = "https://crash-recovery.test",
                    username = "recovered-user",
                    password = "recovered-secret",
                    mdbxDatabaseId = databaseId
                )
            )
            assertTrue(room.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId).isEmpty())

            // Do not let a previous instrumentation class' persisted active-vault
            // selection start an unrelated preload in this recovery scenario.
            context.getSharedPreferences("mdbx_active_vault", 0)
                .edit()
                .remove("last_active_mdbx_database_id")
                .commit()
            val viewModel = MdbxViewModel(
                application,
                databaseDao,
                room.mdbxRemoteSourceDao(),
                room.passwordEntryDao(),
                room.secureItemDao(),
                room.passkeyDao(),
                room.attachmentDao(),
                room.customFieldDao(),
                securityManager
            )
            viewModel.activateMdbxDatabase(databaseId)

            val restored = withTimeout(60_000) {
                while (true) {
                    room.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId)
                        .singleOrNull()
                        ?.let { return@withTimeout it }
                    delay(100)
                }
                error("unreachable")
            }
            assertEquals("Vault-ahead recovery marker", restored.title)
            assertEquals("recovered-user", restored.username)
            assertEquals("password:0", restored.replicaGroupId)
            viewModel.forgetActiveMdbxDatabaseIf(databaseId)
        } finally {
            if (databaseId > 0L) {
                room.passwordEntryDao().deleteAllByMdbxDatabaseId(databaseId)
                room.secureItemDao().deleteAllByMdbxDatabaseId(databaseId)
                room.passkeyDao().deleteAllByMdbxDatabaseId(databaseId)
                databaseDao.deleteDatabaseById(databaseId)
            }
            repository.deleteOwnedVaultFile(File(vaultFile.absolutePath))
        }
    }
}
