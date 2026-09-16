package takagi.ru.monica.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ApiTokenPayload
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.MdbxObjectDisclosureLimits

@RunWith(AndroidJUnit4::class)
class NativeApiTokenPerformanceInstrumentedTest {
    @Test fun openingDetailAfterTheListDoesNotRepeatPasswordDerivation() = runBlocking {
        assumeTrue("Manual benchmark; pass -e apiTokenPerf true",
            InstrumentationRegistry.getArguments().getString("apiTokenPerf") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = PasswordDatabase.getDatabase(context).localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        val wasUnlocked = SessionManager.isUnlocked.value
        val wasForeground = Mdbx2NativeReadSessions.isForeground
        Mdbx2NativeReadSessions.updateForeground(true)
        SessionManager.markUnlocked()
        val password = "Synthetic token performance vault password 123"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.MULTI, password)
        var databaseId = 0L
        try {
            databaseId = dao.insertDatabase(LocalMdbxDatabase(
                name = "Synthetic token performance", filePath = file.absolutePath,
                storageLocation = MdbxStorageLocation.INTERNAL.name,
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                encryptedPassword = security.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
            val payload = """{"schema":"monica.api-token.v1","provider":"Example","api_base":"https://example.test/","token":"synthetic-performance-token"}"""
            val summary = repository.saveNativeApiToken(databaseId, null, "Performance token", payload)
            val opens = mutableListOf<Long>()
            val reveals = mutableListOf<Long>()
            val details = mutableListOf<Long>()
            repeat(5) {
                var revealMs = 0L
                val openedAt = SystemClock.elapsedRealtime()
                repository.withVaultForSync(databaseId) { _, vault ->
                    val revealedAt = SystemClock.elapsedRealtime()
                    val entry = vault.revealObjectWithLimits(summary.entryId,
                        MdbxObjectDisclosureLimits(ApiTokenPayload.MAX_BYTES.toULong())).`object`
                    assertEquals(ApiTokenPayload.decode(payload), entry?.payloadJson?.let(ApiTokenPayload::decode))
                    NativeApiTokenFavorites.assignments(vault, summary.entryId, summary.collectionId)
                    NativeApiTokenExtrasStore.read(vault, summary.entryId)
                    revealMs = SystemClock.elapsedRealtime() - revealedAt
                }
                opens += SystemClock.elapsedRealtime() - openedAt - revealMs
                reveals += revealMs
                assertEquals(summary.entryId, repository.listNativeApiTokens(databaseId).single().entryId)
                val detailAt = SystemClock.elapsedRealtime()
                val detail = repository.readNativeApiToken(databaseId, summary.entryId)
                details += SystemClock.elapsedRealtime() - detailAt
                assertEquals(ApiTokenPayload.decode(payload), ApiTokenPayload.decode(detail.payload))
            }
            val openMedian = opens.sorted()[opens.size / 2]
            val detailMedian = details.sorted()[details.size / 2]
            val output = "API_TOKEN_DETAIL_PERF opens_ms=$opens reveals_ms=$reveals details_ms=$details " +
                "open_median_ms=$openMedian detail_median_ms=$detailMedian"
            Log.i("ApiTokenPerformance", output)
            println(output)
            assertTrue("A detail read after listing should reuse the unlocked vault: $output",
                detailMedian * 2 < openMedian)
        } finally {
            Mdbx2NativeReadSessions.clear()
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
            if (databaseId != 0L) dao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(file)
        }
    }
}
