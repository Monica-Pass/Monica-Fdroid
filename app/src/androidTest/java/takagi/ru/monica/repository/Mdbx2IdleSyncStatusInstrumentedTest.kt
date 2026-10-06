package takagi.ru.monica.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.mdbxPathPendingSyncCount
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.MdbxViewModel

/** Real native vault and durable sync cursor, synthetic rows/files only. */
class Mdbx2IdleSyncStatusInstrumentedTest {
    @Test fun activatingVaultAndOpeningDetailsRepeatedlyKeepsSyncedDataClean() = runBlocking {
        withFixture(MdbxTigaMode.MULTI) { fixture ->
            fixture.repository.upsertPassword(PasswordEntry(id = 45, title = "Synthetic reopen login",
                username = "fixture", password = "synthetic-password", website = "https://example.invalid",
                mdbxDatabaseId = fixture.id))
            val token = fixture.repository.saveNativeApiToken(fixture.id, null, "Synthetic reopen token",
                """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.invalid/api/","token":"synthetic-token"}""")
            fixture.synced()
            val before = fixture.state.read(fixture.id).exportCheckpoint
            // Exercise production activation/preload and detail reads. Scope startup
            // enumeration and preferences to this fixture, never other test/user vaults.
            val scopedDao = object : LocalMdbxDatabaseDao by fixture.dao {
                override fun getAllDatabases() = fixture.dao.getAllDatabases().map { rows -> rows.filter { it.id == fixture.id } }
                override suspend fun getAllDatabasesSnapshot() = listOf(fixture.record())
            }
            val application = object : Application() {
                init { attachBaseContext(fixture.device) }
                override fun getApplicationContext(): Context = this
            }
            repeat(3) {
                Mdbx2NativeReadSessions.clear()
                val store = ViewModelStore()
                val vm = withContext(Dispatchers.Main) {
                    MdbxViewModel(application, scopedDao, fixture.room.mdbxRemoteSourceDao(),
                        fixture.room.passwordEntryDao(), fixture.room.secureItemDao(), fixture.room.passkeyDao(),
                        fixture.room.attachmentDao(), fixture.room.customFieldDao(), fixture.security).also {
                        store.put("fixture", it)
                        it.activateMdbxDatabase(fixture.id)
                    }
                }
                try {
                    withTimeout(30_000) {
                        while (vm.vaultDiagnostics.value[fixture.id] == null) delay(50)
                    }
                    val password = fixture.room.passwordEntryDao().getByMdbxDatabaseIdSync(fixture.id).single()
                    assertEquals("synthetic-password", fixture.security.decryptData(password.password))
                    assertEquals(token.entryId, vm.readNativeApiToken(fixture.id, token.entryId).summary.entryId)
                    assertEquals(MdbxSyncStatus.IN_SYNC.name, fixture.record().lastSyncStatus)
                    assertEquals(0, fixture.repository.getVaultDiagnostics(fixture.id).pendingSyncCount)
                    assertEquals(before, fixture.state.read(fixture.id).exportCheckpoint)
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                }
            }
        }
    }

    @Test fun detailsAndRepositoryRestartDoNotBecomeEditsInLegacyTigaModes() = runBlocking {
        // Exercise all three profiles supported by the Android client.
        for (mode in listOf(MdbxTigaMode.MULTI, MdbxTigaMode.SKY, MdbxTigaMode.POWER)) withFixture(mode) { fixture ->
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.invalid/api/","token":"synthetic-token"}"""
            val token = fixture.repository.saveNativeApiToken(fixture.id, null, "Synthetic token", payload)
            fixture.synced()
            val before = fixture.state.read(fixture.id)
            repeat(3) {
                // Reopen with the same durable Room cursor and device identity, as after restart.
                Mdbx2NativeReadSessions.clear()
                val reopened = Mdbx2Repository(fixture.device, fixture.dao, fixture.security)
                reopened.listFolders(fixture.id)
                reopened.listSnapshots(fixture.id)
                reopened.readStoredEntries(fixture.id)
                if (mode == MdbxTigaMode.POWER) {
                    // The standard test device cannot disclose POWER secrets.
                    // A denied detail read is still not a content edit.
                    val denied = runCatching { reopened.readNativeApiToken(fixture.id, token.entryId) }.exceptionOrNull()
                    assertTrue(denied is IllegalStateException && denied.message == "Native token disclosure was not authorized")
                } else {
                    assertEquals(token.entryId, reopened.readNativeApiToken(fixture.id, token.entryId).summary.entryId)
                }
                assertEquals(0, reopened.getVaultDiagnostics(fixture.id).pendingSyncCount)
                fixture.dao.updateSyncStatus(fixture.id, MdbxSyncStatus.FAILED.name, "Synthetic offline check")
                assertEquals("Reading $mode after a failed background check must not be an edit", 0,
                    reopened.getPendingSyncCount(fixture.id))
            }
            assertEquals("Reading must not consume unpublished audit records", before, fixture.state.read(fixture.id))
            val report = fixture.coordinator.synchronize(fixture.id, fixture.path, fixture.transport)
            if (mode != MdbxTigaMode.SKY) assertTrue("Audit records remain uploadable", report.uploadedSegments > 0)
            assertEquals(MdbxSyncStatus.IN_SYNC, fixture.repository.completeRemoteSync(fixture.id, report))
        }
    }

    @Test fun snapshotsAndTagChangesRemainPendingUntilSynced() = runBlocking {
        withFixture { fixture ->
            val folder = fixture.repository.createFolder(fixture.id, "Synthetic folder", null).folderId
            fixture.synced()
            val snapshot = fixture.repository.createSnapshot(fixture.id, "Synthetic snapshot", true, false)
            assertTrue(fixture.repository.getPendingSyncCount(fixture.id) > 0)
            fixture.dao.updateSyncStatus(fixture.id, MdbxSyncStatus.FAILED.name, "Synthetic offline check")
            assertTrue(fixture.repository.getPendingSyncCount(fixture.id) > 0)
            fixture.synced()
            fixture.repository.deleteSnapshot(fixture.id, snapshot.snapshotId)
            assertTrue(fixture.repository.getPendingSyncCount(fixture.id) > 0)
            fixture.synced()
            fixture.repository.setProjectTags(fixture.id, folder, listOf("changed"))
            assertTrue(fixture.repository.getPendingSyncCount(fixture.id) > 0)
            fixture.synced()
        }
    }

    @Test fun oldCursorsRemainReadableAndWrongVaultCursorsCannotHideEdits() = runBlocking {
        withFixture { fixture ->
            fixture.synced()
            val synced = fixture.state.read(fixture.id)
            fixture.state.write(fixture.id, synced.copy(syncedCommitInventory = null))
            fixture.dao.updateSyncStatus(fixture.id, MdbxSyncStatus.FAILED.name, "Synthetic offline check")
            assertEquals(0, fixture.repository.getPendingSyncCount(fixture.id))
            fixture.state.write(fixture.id, synced.copy(vaultId = "different-vault"))
            assertTrue(fixture.repository.getPendingSyncCount(fixture.id) > 0)
        }
    }

    @Test fun connectionFailureWithoutEditsDoesNotInventAnUnsyncedItem() = runBlocking {
        withFixture { fixture ->
            fixture.synced()
            fixture.dao.updateSyncStatus(fixture.id, MdbxSyncStatus.FAILED.name, "Synthetic connection failure")
            assertEquals(0, fixture.repository.getPendingSyncCount(fixture.id))
            val diagnostics = fixture.repository.getVaultDiagnostics(fixture.id)
            assertEquals(0, diagnostics.pendingSyncCount)
            assertEquals(0, fixture.record().mdbxPathPendingSyncCount(diagnostics.pendingSyncCount))
            assertEquals("Connection error must remain visible", MdbxSyncStatus.FAILED.name, fixture.record().lastSyncStatus)
        }
    }

    @Test fun noOpMaintenanceAndRepeatedReadsDoNotMarkTheVaultDirty() = runBlocking {
        withFixture { fixture ->
            val root = fixture.repository.createFolder(fixture.id, "Synthetic folder", null).folderId
            fixture.repository.setProjectTags(fixture.id, root, listOf("fixture"))
            fixture.synced()
            repeat(3) {
                fixture.repository.listFolders(fixture.id)
                fixture.repository.listSnapshots(fixture.id)
                fixture.repository.getVaultDiagnostics(fixture.id)
                fixture.repository.setProjectTags(fixture.id, root, listOf("fixture"))
                assertEquals(0, fixture.repository.pruneAutomaticSnapshots(fixture.id, 10_000, null))
                assertEquals(MdbxSyncStatus.IN_SYNC.name, fixture.record().lastSyncStatus)
                assertEquals(0, fixture.repository.getPendingSyncCount(fixture.id))
            }
        }
    }

    @Test fun aRealEditRemainsPendingThroughFailureAndClearsOnlyAfterPublication() = runBlocking {
        withFixture { fixture ->
            fixture.synced()
            fixture.repository.upsertPassword(PasswordEntry(id = 45, title = "Synthetic pending entry",
                username = "fixture", password = "test-only", website = "https://example.invalid", mdbxDatabaseId = fixture.id))
            assertTrue(fixture.repository.getPendingSyncCount(fixture.id) > 0)
            fixture.dao.updateSyncStatus(fixture.id, MdbxSyncStatus.FAILED.name, "Synthetic connection failure")
            assertTrue(fixture.repository.getPendingSyncCount(fixture.id) > 0)
            fixture.synced()
            assertEquals(0, fixture.repository.getPendingSyncCount(fixture.id))
            assertTrue(fixture.repository.readStoredEntries(fixture.id).any { it.title == "Synthetic pending entry" })
        }
    }

    private suspend fun withFixture(mode: MdbxTigaMode = MdbxTigaMode.SKY, block: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Fixture(context, mode)
        try { fixture.initialize(); block(fixture) } finally { fixture.close() }
    }

    private class Fixture(context: Context, private val mode: MdbxTigaMode) {
        val device = DeviceContext(context)
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(device, dao, security)
        val root = File(context.cacheDir, "idle-sync-${UUID.randomUUID()}").apply { mkdirs() }
        val transport = DirectoryTransport(File(root, "remote").apply { mkdirs() })
        val state = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val coordinator = Mdbx2RemoteSyncCoordinator(File(root, "sync"), Mdbx2RepositorySyncSessionProvider(repository), state)
        val path = "vaults/idle.mdbx"
        var id = 0L
        var vaultFile: File? = null
        suspend fun initialize() {
            val password = "Synthetic idle sync password"
            val file = repository.createInitializedVaultFile(mode, password).also { vaultFile = it }
            id = dao.insertDatabase(LocalMdbxDatabase(name = "Synthetic idle sync", filePath = path,
                workingCopyPath = file.absolutePath, cacheCopyPath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
                tigaMode = mode.name,
                storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                encryptedPassword = security.encryptData(password), lastSyncStatus = MdbxSyncStatus.PENDING_UPLOAD.name))
            coordinator.publishBootstrap(id, path, transport)
        }
        suspend fun synced() {
            val report = coordinator.synchronize(id, path, transport)
            assertEquals(MdbxSyncStatus.IN_SYNC, repository.completeRemoteSync(id, report))
        }
        suspend fun record() = requireNotNull(dao.getDatabaseById(id))
        suspend fun close() {
            if (id > 0) {
                room.passwordEntryDao().deleteAllByMdbxDatabaseId(id)
                room.secureItemDao().deleteAllByMdbxDatabaseId(id)
                room.passkeyDao().deleteAllByMdbxDatabaseId(id)
                coordinator.clearLocalState(id)
                dao.deleteDatabaseById(id)
            }
            vaultFile?.let { repository.deleteOwnedVaultFile(it) }
            device.clear()
            check(root.canonicalFile.parentFile == device.cacheDir.canonicalFile && root.name.startsWith("idle-sync-"))
            root.deleteRecursively()
        }
    }

    private class DeviceContext(base: Context) : ContextWrapper(base) {
        private val suffix = "-idle-sync-${UUID.randomUUID()}"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = name + suffix
            names += isolated
            return super.getSharedPreferences(isolated, mode)
        }
        fun clear() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    private class DirectoryTransport(private val root: File) : MdbxRemoteTransport {
        private fun file(path: String) = File(root, MdbxRemoteSyncPaths.normalizePath(path))
        private fun info(file: File) = MdbxRemoteObject(file.relativeTo(root).invariantSeparatorsPath,
            file.isDirectory, file.length(), file.lastModified().toString())
        override suspend fun testConnection() = Unit
        override suspend fun stat(path: String) = file(path).takeIf { it.exists() }?.let(::info)
        override suspend fun list(path: String?) = (path?.let(::file) ?: root).listFiles().orEmpty().map(::info)
        override suspend fun ensureDirectory(path: String) { check(file(path).let { it.isDirectory || it.mkdirs() }) }
        override suspend fun readTo(path: String, destination: File) { file(path).copyTo(destination, overwrite = true) }
        override suspend fun writeFrom(path: String, source: File, mode: MdbxRemoteWriteMode, expectedVersion: String?): MdbxRemoteObject {
            val target = file(path)
            if (mode == MdbxRemoteWriteMode.CREATE_ONLY && target.exists()) {
                check(source.readBytes().contentEquals(target.readBytes()))
            } else {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
            return info(target)
        }
    }
}
