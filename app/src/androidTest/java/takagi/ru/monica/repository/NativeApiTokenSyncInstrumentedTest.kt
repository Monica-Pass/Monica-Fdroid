package takagi.ru.monica.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ApiTokenPayload
import takagi.ru.monica.data.ApiTokenMetadata
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.MdbxRemoteObject
import takagi.ru.monica.utils.MdbxRemoteSyncPaths
import takagi.ru.monica.utils.MdbxRemoteTransport
import takagi.ru.monica.utils.MdbxRemoteWriteMode

@RunWith(AndroidJUnit4::class)
class NativeApiTokenSyncInstrumentedTest {
    @Test
    fun tokenBodyRemainsReadableWhileRemoteSyncWaitsForNetwork() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val state = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val security = SecurityManager(context)
        val device = DeviceContext(context)
        val repository = Mdbx2Repository(device, dao, security)
        val password = "Synthetic network gate password 123"
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val root = File(context.cacheDir, "token-sync-gate-${UUID.randomUUID()}").apply { mkdirs() }
        val transport = DirectoryTransport(File(root, "remote").apply { mkdirs() })
        val coordinator = Mdbx2RemoteSyncCoordinator(File(root, "sync"),
            Mdbx2RepositorySyncSessionProvider(repository), state)
        val path = "vaults/network-gate.mdbx"
        val databaseId = dao.insertDatabase(LocalMdbxDatabase(
            name = "Synthetic network gate", filePath = path,
            workingCopyPath = vaultFile.absolutePath, cacheCopyPath = vaultFile.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
            sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
            encryptedPassword = security.encryptData(password),
            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name,
        ))
        try {
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://synthetic.example.test/api/v4/","token":"synthetic-network-gate-token"}"""
            val summary = repository.saveNativeApiToken(databaseId, null, "network-gate-token", payload)
            coordinator.publishBootstrap(databaseId, path, transport)
            val networkEntered = CompletableDeferred<Unit>()
            val releaseNetwork = CompletableDeferred<Unit>()
            val delayed = object : MdbxRemoteTransport by transport {
                override suspend fun stat(path: String): MdbxRemoteObject? {
                    if (path == MdbxRemoteSyncPaths.streamsRoot("vaults/network-gate.mdbx")) {
                        networkEntered.complete(Unit)
                        releaseNetwork.await()
                    }
                    return transport.stat(path)
                }
            }
            val synchronization = async(Dispatchers.IO) { coordinator.synchronize(databaseId, path, delayed) }
            try {
                withTimeout(10_000) { networkEntered.await() }
                repeat(3) { index ->
                    val started = SystemClock.elapsedRealtime()
                    val detail = withTimeoutOrNull(2_000) { repository.readNativeApiToken(summary) }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    Log.i("NativeTokenSyncGate", "body read ${index + 1}: ${elapsed}ms, network released=${releaseNetwork.isCompleted}")
                    assertNotNull("Token body waited for the stalled remote request (${elapsed}ms)", detail)
                    assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(checkNotNull(detail).payload))
                    assertTrue("The network must still be paused during disclosure", !releaseNetwork.isCompleted)
                }
                releaseNetwork.complete(Unit)
                withTimeout(10_000) { synchronization.await() }
            } finally {
                releaseNetwork.complete(Unit)
                synchronization.cancelAndJoin()
            }
        } finally {
            state.delete(databaseId)
            dao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(vaultFile)
            device.clearTestPreferences()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeTokenSurvivesFailedUploadRetryAndDownloadWithoutRemainingPending() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val state = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val security = SecurityManager(context)
        val sourceContext = DeviceContext(context)
        val targetContext = DeviceContext(context)
        val source = Mdbx2Repository(sourceContext, dao, security)
        val target = Mdbx2Repository(targetContext, dao, security)
        val password = "Synthetic sync test password 123"
        val sourceFile = source.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val targetFile = File(sourceFile.parentFile, "${UUID.randomUUID()}.mdbx")
        val root = File(context.cacheDir, "native-token-sync-${UUID.randomUUID()}").apply { mkdirs() }
        val transport = DirectoryTransport(File(root, "remote").apply { mkdirs() })
        val sourceSync = Mdbx2RemoteSyncCoordinator(File(root, "source"),
            Mdbx2RepositorySyncSessionProvider(source), state)
        val targetSync = Mdbx2RemoteSyncCoordinator(File(root, "target"),
            Mdbx2RepositorySyncSessionProvider(target), state)
        val ids = mutableListOf<Long>()
        suspend fun register(file: File): Long = dao.insertDatabase(LocalMdbxDatabase(
            name = "Synthetic token sync", filePath = "vaults/synthetic.mdbx",
            workingCopyPath = file.absolutePath, cacheCopyPath = file.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
            sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
            encryptedPassword = security.encryptData(password),
            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name, lastSyncedAt = System.currentTimeMillis(),
        )).also(ids::add)
        try {
            val sourceId = register(sourceFile)
            val path = "vaults/synthetic.mdbx"
            sourceSync.publishBootstrap(sourceId, path, transport)
            targetSync.downloadBootstrapTo(path, transport, targetFile)
            val targetId = register(targetFile)
            targetSync.registerDownloadedBootstrap(targetId, path)
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://synthetic.example.test/api/v4/","token":"synthetic-only-token","note":"sync test","future":{"scope":"api"}}"""
            val metadata = ApiTokenMetadata.withCustomFields(ApiTokenMetadata.withNotes(ApiTokenMetadata.empty(), "Synced app note"),
                listOf(CustomFieldDraft(-1, "Protected scope", "synthetic scope", true)))
            val created = source.saveNativeApiToken(sourceId, null, "synthetic-token", payload, isFavorite = true, metadata = metadata)
            assertEquals(MdbxSyncStatus.PENDING_UPLOAD.name, dao.getDatabaseById(sourceId)?.lastSyncStatus)
            assertTrue(source.getPendingSyncCount(sourceId) > 0)

            transport.failNextSegment = true
            assertTrue(runCatching { sourceSync.synchronize(sourceId, path, transport) }.isFailure)
            assertNotNull(state.read(sourceId).pendingSegment)
            assertTrue(source.getPendingSyncCount(sourceId) > 0)
            assertTrue(sourceSync.synchronize(sourceId, path, transport).uploadedSegments > 0)
            dao.updateSyncSuccess(sourceId, MdbxSyncStatus.IN_SYNC.name, System.currentTimeMillis())
            assertEquals(0, source.getPendingSyncCount(sourceId))
            assertEquals(0, source.getVaultDiagnostics(sourceId).pendingSyncCount)

            assertTrue(targetSync.synchronize(targetId, path, transport).downloadedSegments > 0)
            val received = target.readNativeApiToken(target.listNativeApiTokens(targetId).single())
            assertEquals(created.entryId, received.summary.entryId)
            assertTrue(received.summary.isFavorite)
            assertTrue(target.listNativeApiTokens(targetId).single().isFavorite)
            assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(received.payload))
            assertEquals(ApiTokenMetadata.decode(metadata), ApiTokenMetadata.decode(checkNotNull(received.extras).payload))
            assertEquals(0, sourceSync.synchronize(sourceId, path, transport).uploadedSegments)
            assertEquals(0, source.getPendingSyncCount(sourceId))
        } finally {
            for (id in ids) { state.delete(id); dao.deleteDatabaseById(id) }
            source.deleteOwnedVaultFile(sourceFile)
            target.deleteOwnedVaultFile(targetFile)
            sourceContext.clearTestPreferences()
            targetContext.clearTestPreferences()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test
    fun editsDuringRemoteWaitAndBeforeCompletionStillReachTheOtherReplica() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val state = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val security = SecurityManager(context)
        val sourceContext = DeviceContext(context)
        val targetContext = DeviceContext(context)
        val source = Mdbx2Repository(sourceContext, dao, security)
        val target = Mdbx2Repository(targetContext, dao, security)
        val password = "Synthetic concurrent sync password 123"
        val sourceFile = source.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val targetFile = File(sourceFile.parentFile, "${UUID.randomUUID()}.mdbx")
        val root = File(context.cacheDir, "token-sync-edit-${UUID.randomUUID()}").apply { mkdirs() }
        val transport = DirectoryTransport(File(root, "remote").apply { mkdirs() })
        val sourceSync = Mdbx2RemoteSyncCoordinator(File(root, "source"),
            Mdbx2RepositorySyncSessionProvider(source), state)
        val targetSync = Mdbx2RemoteSyncCoordinator(File(root, "target"),
            Mdbx2RepositorySyncSessionProvider(target), state)
        val path = "vaults/concurrent-edit.mdbx"
        val ids = mutableListOf<Long>()
        suspend fun register(file: File): Long = dao.insertDatabase(LocalMdbxDatabase(
            name = "Synthetic concurrent sync", filePath = path,
            workingCopyPath = file.absolutePath, cacheCopyPath = file.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
            sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
            encryptedPassword = security.encryptData(password),
            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name,
        )).also(ids::add)
        try {
            val sourceId = register(sourceFile)
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://synthetic.example.test/api/v4/","token":"synthetic-original-token"}"""
            val editedPayload = ApiTokenPayload.update(payload, "token", "synthetic-edited-during-sync")
            val original = source.saveNativeApiToken(sourceId, null, "original-token", payload)
            sourceSync.publishBootstrap(sourceId, path, transport)
            targetSync.downloadBootstrapTo(path, transport, targetFile)
            val targetId = register(targetFile)
            targetSync.registerDownloadedBootstrap(targetId, path)
            val targetOriginal = target.readNativeApiToken(targetId, original.entryId)
            val remoteAdded = source.saveNativeApiToken(sourceId, null, "remote-added-token", payload)
            source.completeRemoteSync(sourceId, sourceSync.synchronize(sourceId, path, transport))

            val networkEntered = CompletableDeferred<Unit>()
            val releaseNetwork = CompletableDeferred<Unit>()
            val delayed = object : MdbxRemoteTransport by transport {
                override suspend fun stat(requestPath: String): MdbxRemoteObject? {
                    if (requestPath == MdbxRemoteSyncPaths.streamsRoot(path)) {
                        networkEntered.complete(Unit)
                        releaseNetwork.await()
                    }
                    return transport.stat(requestPath)
                }
            }
            val synchronization = async(Dispatchers.IO) { targetSync.synchronize(targetId, path, delayed) }
            val received = try {
                withTimeout(10_000) { networkEntered.await() }
                val published = state.read(targetId).exportCheckpoint
                withTimeout(2_000) {
                    target.saveNativeApiToken(targetId, targetOriginal, "edited-while-waiting", editedPayload)
                }
                releaseNetwork.complete(Unit)
                withTimeout(10_000) { synchronization.await() }.also { report ->
                    assertTrue(report.downloadedSegments > 0)
                    assertEquals("The remote apply must not acknowledge the concurrent local edit",
                        published, state.read(targetId).exportCheckpoint)
                }
            } finally {
                releaseNetwork.complete(Unit)
                synchronization.cancelAndJoin()
            }
            assertEquals(MdbxSyncStatus.PENDING_UPLOAD, target.completeRemoteSync(targetId, received))
            assertEquals(MdbxSyncStatus.PENDING_UPLOAD.name, dao.getDatabaseById(targetId)?.lastSyncStatus)
            assertEquals(ApiTokenPayload.decode(editedPayload),
                ApiTokenPayload.decode(target.readNativeApiToken(targetId, original.entryId).payload))
            assertEquals(remoteAdded.entryId, target.readNativeApiToken(targetId, remoteAdded.entryId).summary.entryId)

            val publishedEdits = targetSync.synchronize(targetId, path, transport)
            assertTrue(publishedEdits.uploadedSegments > 0)
            // Cover the gap between coordinator completion and ViewModel/Room import completion.
            val lateAdded = target.saveNativeApiToken(targetId, null, "after-sync-token", payload)
            assertEquals(MdbxSyncStatus.PENDING_UPLOAD, target.completeRemoteSync(targetId, publishedEdits))
            val finalPublication = targetSync.synchronize(targetId, path, transport)
            assertTrue(finalPublication.uploadedSegments > 0)
            assertEquals(MdbxSyncStatus.IN_SYNC, target.completeRemoteSync(targetId, finalPublication))

            assertTrue(sourceSync.synchronize(sourceId, path, transport).downloadedSegments > 0)
            val replicated = source.readNativeApiToken(sourceId, original.entryId)
            assertEquals("edited-while-waiting", replicated.summary.title)
            assertEquals(ApiTokenPayload.decode(editedPayload), ApiTokenPayload.decode(replicated.payload))
            assertEquals(lateAdded.entryId, source.readNativeApiToken(sourceId, lateAdded.entryId).summary.entryId)
            assertEquals(0, targetSync.synchronize(targetId, path, transport).uploadedSegments)
        } finally {
            for (id in ids) { state.delete(id); dao.deleteDatabaseById(id) }
            source.deleteOwnedVaultFile(sourceFile)
            target.deleteOwnedVaultFile(targetFile)
            sourceContext.clearTestPreferences()
            targetContext.clearTestPreferences()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test
    fun readingTokenAfterPublicationDoesNotBecomeAnUnsyncedEdit() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val state = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val security = SecurityManager(context)
        val device = DeviceContext(context)
        val repository = Mdbx2Repository(device, dao, security)
        val password = "Synthetic audit sync password 123"
        // MULTI audits disclosures; SKY intentionally does not audit ordinary reads.
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.MULTI, password)
        val root = File(context.cacheDir, "token-audit-sync-${UUID.randomUUID()}").apply { mkdirs() }
        val transport = DirectoryTransport(File(root, "remote").apply { mkdirs() })
        val coordinator = Mdbx2RemoteSyncCoordinator(File(root, "sync"),
            Mdbx2RepositorySyncSessionProvider(repository), state)
        val path = "vaults/audit-sync.mdbx"
        val databaseId = dao.insertDatabase(LocalMdbxDatabase(
            name = "Synthetic audit sync", filePath = path,
            workingCopyPath = vaultFile.absolutePath, cacheCopyPath = vaultFile.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
            sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
            tigaMode = MdbxTigaMode.MULTI.name,
            encryptedPassword = security.encryptData(password),
            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name,
        ))
        try {
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://synthetic.example.test/api/v4/","token":"synthetic-audit-token"}"""
            val token = repository.saveNativeApiToken(databaseId, null, "audit-token", payload)
            coordinator.publishBootstrap(databaseId, path, transport)
            val report = coordinator.synchronize(databaseId, path, transport)
            val published = checkNotNull(report.publishedCheckpoint)
            repository.readNativeApiToken(databaseId, token.entryId)
            val current = repository.withReadVaultForSync(databaseId) { _, vault -> vault.incrementalSyncCheckpoint() }
            assertEquals("Reading must not create an edit commit", published.commitInventory, current.commitInventory)
            assertTrue("The fixture must produce an unpublished audit record", published.deltaInventory != current.deltaInventory)
            assertEquals(MdbxSyncStatus.IN_SYNC, repository.completeRemoteSync(databaseId, report))
            assertEquals(0, repository.getPendingSyncCount(databaseId))
            assertEquals("Read-only audit records must still be eligible for upload",
                published, state.read(databaseId).exportCheckpoint)
            val next = coordinator.synchronize(databaseId, path, transport)
            assertTrue("Audit records must remain in the normal sync stream", next.uploadedSegments > 0)
            assertEquals(MdbxSyncStatus.IN_SYNC, repository.completeRemoteSync(databaseId, next))
        } finally {
            state.delete(databaseId)
            dao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(vaultFile)
            device.clearTestPreferences()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    /** Give each synthetic replica its own device identity without touching the user's settings. */
    private class DeviceContext(base: Context) : ContextWrapper(base) {
        private val suffix = "-token-sync-${UUID.randomUUID()}"
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = name + suffix
            preferences.add(isolated)
            return super.getSharedPreferences(isolated, mode)
        }
        fun clearTestPreferences() { preferences.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    /** Exercise the real Rust protocol with an isolated file transport and a failed publication. */
    private class DirectoryTransport(private val root: File) : MdbxRemoteTransport {
        var failNextSegment = false
        private fun file(path: String): File = File(root, MdbxRemoteSyncPaths.normalizePath(path))
        private fun info(file: File) = MdbxRemoteObject(file.relativeTo(root).invariantSeparatorsPath,
            file.isDirectory, file.length(), file.lastModified().toString())
        override suspend fun testConnection() = Unit
        override suspend fun stat(path: String) = file(path).takeIf { it.exists() }?.let(::info)
        override suspend fun list(path: String?) = (path?.let(::file) ?: root)
            .listFiles().orEmpty().map(::info).sortedBy { it.path }
        override suspend fun ensureDirectory(path: String) { check(file(path).let { it.isDirectory || it.mkdirs() }) }
        override suspend fun readTo(path: String, destination: File) {
            destination.parentFile?.mkdirs()
            file(path).copyTo(destination, overwrite = true)
        }
        override suspend fun writeFrom(path: String, source: File, mode: MdbxRemoteWriteMode,
            expectedVersion: String?): MdbxRemoteObject {
            if (failNextSegment && path.endsWith(".mdbxsync")) {
                failNextSegment = false
                throw IOException("Synthetic upload interruption")
            }
            val destination = file(path)
            if (mode == MdbxRemoteWriteMode.CREATE_ONLY && destination.exists()) {
                check(source.readBytes().contentEquals(destination.readBytes()))
                return info(destination)
            }
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
            return info(destination)
        }
    }
}
