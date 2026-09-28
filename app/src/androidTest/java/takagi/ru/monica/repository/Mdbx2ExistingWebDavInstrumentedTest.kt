package takagi.ru.monica.repository

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxRemoteSource
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.WebDavMdbxRemoteTransport
import uniffi.mdbx_ffi.createPortableBackup

/** Opt-in live test. Supply credentials in a private, untracked device fixture, never test arguments. */
@RunWith(AndroidJUnit4::class)
class Mdbx2ExistingWebDavInstrumentedTest {
    @Test
    fun existingVaultHistoryConvergesAfterCreateEditAndReopen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "mdbx-existing-webdav-test.json")
        assumeTrue("Private existing-WebDAV fixture was not supplied", fixture.isFile)
        val config = JSONObject(fixture.readText())
        check(fixture.delete())
        val ownedRoot = config.getString("ownedRoot")
        val remotePath = config.getString("remotePath")
        require(ownedRoot.matches(Regex("MonicaQA-[a-f0-9]{8}")))
        require(remotePath == "$ownedRoot/vault.mdbx") {
            "Live writes must target the isolated diagnostic copy"
        }
        val serverUrl = config.getString("serverUrl")
        val username = config.getString("username")
        val password = config.getString("password")
        val vaultPassword = config.getString("vaultPassword")
        val runId = UUID.randomUUID().toString()
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val sourceDao = room.mdbxRemoteSourceDao()
        val security = SecurityManager(context)
        val states = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val preferences = context.getSharedPreferences("mdbx2_vault_sessions", Context.MODE_PRIVATE)
        val originalDeviceId = preferences.getString("device_id", null)
        val files = mutableListOf<File>()
        val databaseIds = mutableListOf<Long>()
        val syncRoots = mutableListOf<File>()
        var sourceId: Long? = null
        val started = System.currentTimeMillis()
        val transport = WebDavMdbxRemoteTransport(
            serverUrl, username, password, AppLocaleStringResolver(context)
        )

        suspend fun replica(label: String): Triple<Mdbx2Repository, Mdbx2RemoteSyncCoordinator, Long> {
            check(preferences.edit().putString("device_id", "monica-qa-$label-$runId").commit())
            val repository = Mdbx2Repository(context, dao, security)
            val root = File(context.cacheDir, "mdbx-live-$label-$runId").also(syncRoots::add)
            val coordinator = Mdbx2RemoteSyncCoordinator(root, Mdbx2RepositorySyncSessionProvider(repository), states)
            val file = File(File(context.filesDir, "mdbx2").apply { mkdirs() }, "live-$label-$runId.mdbx")
            files += file
            coordinator.downloadBootstrapTo(remotePath, transport, file)
            repository.validatePasswordVaultFile(file, vaultPassword)
            val id = dao.insertDatabase(LocalMdbxDatabase(
                name = "Live synchronization $label $runId",
                filePath = remotePath,
                workingCopyPath = file.absolutePath,
                cacheCopyPath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
                storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                sourceId = sourceId,
                encryptedPassword = security.encryptData(vaultPassword),
                isOfflineAvailable = true
            ))
            databaseIds += id
            coordinator.registerDownloadedBootstrap(id, remotePath)
            return Triple(repository, coordinator, id)
        }

        try {
            withTimeout(10 * 60_000L) {
                sourceId = sourceDao.insertSource(MdbxRemoteSource(
                    displayName = "Live sync fixture $runId", remotePath = remotePath,
                    remoteParentPath = ownedRoot, baseUrl = serverUrl,
                    usernameEncrypted = security.encryptData(username),
                    passwordEncrypted = security.encryptData(password)
                ))
                Log.i(TAG, "Download existing bootstrap A")
                val (a, syncA, idA) = replica("a")
                val bootstrapIds = a.readStoredEntries(idA).map { it.entryId }.toSet()
                Log.i(TAG, "Synchronize existing history A")
                val initial = syncA.synchronize(idA, remotePath, transport)
                assertEquals("Existing historical streams must apply", 0, initial.blockedStreams)
                val historyIds = a.readStoredEntries(idA).map { it.entryId }.toSet()
                assertTrue("Initial synchronization must preserve bootstrap entries", historyIds.containsAll(bootstrapIds))

                val added = PasswordEntry(
                    id = 8_500_000_000L + (System.nanoTime() and 0xfffff),
                    title = "Monica live verification $runId", username = "created-by-a",
                    password = "", website = "https://example.invalid/",
                    notes = "Synthetic sync regression", mdbxDatabaseId = idA
                )
                a.upsertPassword(added)
                Log.i(TAG, "Publish local entry A")
                val published = syncA.synchronize(idA, remotePath, transport)
                assertTrue("A local edit must publish a segment", published.uploadedSegments > 0)
                val created = a.readStoredEntries(idA).single { it.title == added.title }

                Log.i(TAG, "Download and synchronize replica B")
                val (b, syncB, idB) = replica("b")
                val received = syncB.synchronize(idB, remotePath, transport)
                assertEquals("Replica B must receive all historical dependencies", 0, received.blockedStreams)
                val replicaEntries = b.readStoredEntries(idB)
                assertTrue("Replica B must preserve original entry ids", replicaEntries.map { it.entryId }.containsAll(historyIds))
                val replicated = replicaEntries.single { it.entryId == created.entryId }
                assertTrue("An empty password must survive cloud synchronization",
                    JSONObject(replicated.payloadJson).getString("password_plain").isEmpty())
                b.upsertPassword(added.copy(mdbxDatabaseId = idB, username = "edited-by-b"))
                Log.i(TAG, "Publish B edit and synchronize A")
                syncB.synchronize(idB, remotePath, transport)
                syncA.synchronize(idA, remotePath, transport)
                val reopened = Mdbx2Repository(context, dao, security).readStoredEntries(idA)
                assertTrue("Original entries must survive restart", reopened.map { it.entryId }.containsAll(historyIds))
                assertTrue("Replica B edit must survive reopening A",
                    JSONObject(reopened.single { it.entryId == created.entryId }.payloadJson).getString("username") == "edited-by-b")
                val output = File(context.filesDir, "mdbx-live-verified.mdbx")
                if (output.exists()) check(output.delete())
                createPortableBackup(files.first().absolutePath, output.absolutePath)
                File(context.filesDir, "mdbx-live-result.json").writeText(JSONObject()
                    .put("result", "passed").put("bootstrapEntries", bootstrapIds.size)
                    .put("synchronizedEntries", historyIds.size).put("elapsedMs", System.currentTimeMillis() - started)
                    .put("historySegmentsReceived", initial.downloadedSegments)
                    .put("emptyPasswordPreserved", true).put("bidirectionalEditPreserved", true).toString(2))
                Log.i(TAG, "Existing WebDAV create, edit, history and reopen passed")
            }
        } finally {
            databaseIds.forEach { id ->
                runCatching { states.delete(id) }
                runCatching { dao.deleteDatabaseById(id) }
            }
            sourceId?.let { sourceDao.deleteSourceById(it) }
            val cleaner = Mdbx2Repository(context, dao, security)
            files.forEach { runCatching { cleaner.deleteOwnedVaultFile(it) } }
            syncRoots.forEach { it.deleteRecursively() }
            preferences.edit().apply {
                if (originalDeviceId == null) remove("device_id") else putString("device_id", originalDeviceId)
            }.commit()
        }
    }

    private companion object { const val TAG = "MdbxLiveVerification" }
}
