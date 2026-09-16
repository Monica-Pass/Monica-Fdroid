package takagi.ru.monica.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ApiTokenPayload
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.MdbxObjectDisclosureLimits
import uniffi.mdbx_ffi.MdbxWriteCommand

/** Opt-in device benchmark of the actual CLI object and repository navigation sequence. */
@RunWith(AndroidJUnit4::class)
class NativeApiTokenColdPerformanceInstrumentedTest {
    @Test fun detailWithManyCollectionsAndLabels() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual metadata benchmark; pass -e apiTokenMetadataPerf true",
            arguments.getString("apiTokenMetadataPerf") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = PasswordDatabase.getDatabase(context).localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        val wasUnlocked = SessionManager.isUnlocked.value
        val wasForeground = Mdbx2NativeReadSessions.isForeground
        Mdbx2NativeReadSessions.updateForeground(true)
        SessionManager.markUnlocked()
        val password = "Synthetic CLI metadata performance password 123"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.MULTI, password)
        var databaseId = 0L
        try {
            databaseId = dao.insertDatabase(LocalMdbxDatabase(
                name = "Synthetic CLI metadata performance", filePath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                encryptedPassword = security.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
            val folder = repository.createFolder(databaseId, "CLI metadata folder", null)
            val entryId = UUID.randomUUID().toString()
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.test/","token":"synthetic-metadata-token","future":{"scope":"api"}}"""
            repository.withVaultForSync(databaseId) { _, vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-metadata-create", listOf(
                    MdbxWriteCommand.CreateEntry(entryId, folder.folderId, "api-token", "CLI token", payload)))
            }
            suspend fun sample(count: Int): List<Long> {
                repository.listNativeApiTokens(databaseId)
                return (0..2).map {
                    val start = SystemClock.elapsedRealtime()
                    val detail = repository.readNativeApiToken(databaseId, entryId)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(detail.payload))
                    Log.i(TAG, "CLI_METADATA count=$count detail_ms=$elapsed")
                    elapsed
                }
            }
            val small = sample(0)
            val count = 300
            repository.withVaultForSync(databaseId) { _, vault ->
                val commands = buildList {
                    repeat(count) { index ->
                        add(MdbxWriteCommand.CreateProjectWithParent(UUID.randomUUID().toString(), "Unrelated $index", null))
                        add(MdbxWriteCommand.CreateObjectLabel(UUID.randomUUID().toString(), folder.folderId,
                            "unrelated-label-$index", "{}", 1u))
                    }
                }
                // Exercise a large catalog without bypassing the production 256-command limit.
                commands.chunked(200).forEach { batch ->
                    vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-metadata-expand", batch)
                }
            }
            val large = sample(count)
            Log.i(TAG, "CLI_METADATA_PERF extra_collections=$count extra_labels=$count small_ms=$small large_ms=$large")
        } finally {
            Mdbx2NativeReadSessions.clear()
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
            if (databaseId != 0L) dao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(file)
        }
    }

    @Test fun detailAfterBrowsingAndFolderReads() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual benchmark; pass -e apiTokenPerf true",
            arguments.getString("apiTokenPerf") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = PasswordDatabase.getDatabase(context).localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        val executor = Mdbx2VaultSessionExecutor(context, dao, security)
        val wasUnlocked = SessionManager.isUnlocked.value
        val wasForeground = Mdbx2NativeReadSessions.isForeground
        Mdbx2NativeReadSessions.updateForeground(true)
        SessionManager.markUnlocked()
        val password = "Synthetic CLI navigation performance password 123"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.MULTI, password)
        var databaseId = 0L
        try {
            databaseId = dao.insertDatabase(LocalMdbxDatabase(
                name = "Synthetic CLI navigation performance", filePath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                encryptedPassword = security.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
            val folder = repository.createFolder(databaseId, "CLI folder", null)
            val entryId = UUID.randomUUID().toString()
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.test/api/v4/","token":"synthetic-navigation-token","future":{"scope":"api"}}"""
            repository.withVaultForSync(databaseId) { _, vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-cli-create", listOf(
                    MdbxWriteCommand.CreateEntry(entryId, folder.folderId, "api-token", "CLI token", payload)))
            }

            suspend fun sessionId() = executor.withNativeReadVault(databaseId) { _, vault ->
                checkNotNull(vault.activeSessionInfo()).sessionId
            }

            suspend fun detail(case: String): Long {
                val start = SystemClock.elapsedRealtime()
                val token = repository.readNativeApiToken(databaseId, entryId)
                val elapsed = SystemClock.elapsedRealtime() - start
                assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(token.payload))
                assertEquals("CLI folder", token.summary.collectionTitle)
                Log.i(TAG, "CLI_DETAIL case=$case total_ms=$elapsed")
                return elapsed
            }

            suspend fun stages(case: String) {
                val start = SystemClock.elapsedRealtime()
                executor.withNativeReadVault(databaseId) { _, vault ->
                    val entered = SystemClock.elapsedRealtime()
                    val entry = checkNotNull(vault.revealObjectWithLimits(entryId,
                        MdbxObjectDisclosureLimits(ApiTokenPayload.MAX_BYTES.toULong())).`object`)
                    val revealed = SystemClock.elapsedRealtime()
                    var cursor: String? = null
                    var collections = 0
                    do {
                        val page = vault.listCollectionSummaries(100u, cursor)
                        collections += page.items.size
                        cursor = page.nextCursor
                    } while (cursor != null)
                    val hierarchy = SystemClock.elapsedRealtime()
                    NativeApiTokenFavorites.assignments(vault, entryId, entry.collectionId)
                    val favorites = SystemClock.elapsedRealtime()
                    NativeApiTokenExtrasStore.read(vault, entryId)
                    val extras = SystemClock.elapsedRealtime()
                    Log.i(TAG, "CLI_STAGES case=$case enter_ms=${entered - start} " +
                        "reveal_ms=${revealed - entered} hierarchy_ms=${hierarchy - revealed} " +
                        "favorites_ms=${favorites - hierarchy} extras_ms=${extras - favorites} " +
                        "collections=$collections")
                }
            }

            // No payload prefetch: this is the real metadata list used by the password/vault UI.
            repository.listNativeApiTokens(databaseId)
            val warmSession = sessionId()
            val warm = mutableListOf<Long>()
            repeat(3) { warm += detail("immediate-list-$it") }
            stages("warm")

            val afterFolders = mutableListOf<Long>()
            repeat(3) {
                repository.listNativeApiTokens(databaseId)
                val start = SystemClock.elapsedRealtime()
                repository.listFolders(databaseId)
                Log.i(TAG, "CLI_FOLDERS total_ms=${SystemClock.elapsedRealtime() - start}")
                afterFolders += detail("after-folder-read-$it")
            }
            val reusedAfterFolders = warmSession == sessionId()

            repository.listNativeApiTokens(databaseId)
            val beforePause = sessionId()
            delay(31_000)
            val afterPause = detail("after-31s-browsing")
            val reusedAfterPause = beforePause == sessionId()
            Mdbx2NativeReadSessions.clear()
            stages("cold")
            val output = "CLI_NAVIGATION_PERF warm_ms=$warm after_folders_ms=$afterFolders " +
                "after_31s_ms=$afterPause reused_after_folders=$reusedAfterFolders " +
                "reused_after_31s=$reusedAfterPause"
            Log.i(TAG, output)
            println(output)
            if (arguments.getString("apiTokenPerfExpectReuse") == "true") {
                assertTrue("Ordinary browsing should preserve the native unlock: $output", reusedAfterFolders)
                assertTrue("Browsing for 31 seconds should not repeat password derivation: $output", reusedAfterPause)
            }
        } finally {
            Mdbx2NativeReadSessions.clear()
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
            if (databaseId != 0L) dao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(file)
        }
    }

    companion object {
        private const val TAG = "ApiTokenPerformance"
    }
}
