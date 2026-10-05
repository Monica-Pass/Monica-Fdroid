package takagi.ru.monica.repository

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager

class MdbxAutoSyncSchedulingTest {
    @Test fun committedEditsQueueDurableWorkAndReadonlyAccessDoesNot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repo = Mdbx2Repository(context, dao, security)
        val file = repo.createInitializedVaultFile(MdbxTigaMode.SKY, "synthetic-auto-sync")
        val id = dao.insertDatabase(LocalMdbxDatabase(name = "Auto sync scheduling fixture", filePath = file.absolutePath,
            workingCopyPath = file.absolutePath, cacheCopyPath = file.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
            encryptedPassword = security.encryptData("synthetic-auto-sync"),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name))
        val work = WorkManager.getInstance(context)
        val key = "mdbx-auto-$id"
        val preferences = takagi.ru.monica.workers.MdbxAutoSyncPreferences(context)
        try {
            assertFalse("Existing vaults default to manual", preferences.isEnabled(id))
            repo.readStoredEntries(id)
            assertTrue(work.getWorkInfosForUniqueWork(key).get().isEmpty())
            repo.upsertPassword(PasswordEntry(id = 850001L, title = "First", username = "a", password = "fixture", website = "https://example.test", mdbxDatabaseId = id))
            delay(200)
            assertTrue("Manual save must not schedule cloud work", work.getWorkInfosForUniqueWork(key).get().isEmpty())
            preferences.setEnabled(id, true)
            repo.upsertPassword(PasswordEntry(id = 850002L, title = "Second", username = "b", password = "fixture", website = "https://example.test", mdbxDatabaseId = id))
            repeat(40) { takagi.ru.monica.workers.MdbxAutoSyncWorker.enqueue(context, id) }
            repeat(40) {
                delay(50)
            }
            val jobs = work.getWorkInfosForUniqueWork(key).get()
            assertTrue("Burst requests share one queued job, or one running job plus successor", jobs.size in 1..2)
            assertTrue(jobs.none { it.state == androidx.work.WorkInfo.State.CANCELLED })
            assertEquals(MdbxSyncStatus.PENDING_UPLOAD.name, dao.getDatabaseById(id)!!.lastSyncStatus)
            assertEquals(2, repo.readStoredEntries(id).count { !it.deleted })
            preferences.setEnabled(id, false)
            val wasUnlocked = takagi.ru.monica.security.SessionManager.isUnlocked.value
            try {
                takagi.ru.monica.security.SessionManager.markUnlocked()
                kotlinx.coroutines.withTimeout(15_000) {
                    while (work.getWorkInfosForUniqueWork(key).get().any { !it.state.isFinished }) delay(100)
                }
                assertTrue("Opted-out jobs exit successfully before attempting the deliberately absent remote source",
                    work.getWorkInfosForUniqueWork(key).get().all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                assertEquals(MdbxSyncStatus.PENDING_UPLOAD.name, dao.getDatabaseById(id)!!.lastSyncStatus)
            } finally {
                if (!wasUnlocked) takagi.ru.monica.security.SessionManager.markLocked()
            }
        } finally {
            preferences.setEnabled(id, false)
            work.cancelUniqueWork(key).result.get()
            dao.deleteDatabaseById(id)
            repo.deleteOwnedVaultFile(file)
        }
    }
}
