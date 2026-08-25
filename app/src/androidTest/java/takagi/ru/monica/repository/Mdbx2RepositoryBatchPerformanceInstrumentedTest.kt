package takagi.ru.monica.repository

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager

@RunWith(AndroidJUnit4::class)
class Mdbx2RepositoryBatchPerformanceInstrumentedTest {

    @Test
    fun monicaRepositoryBatchPerformanceBaseline() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Manual performance test; pass -e mdbxPerf true",
            arguments.getString(PERF_ARGUMENT).equals("true", ignoreCase = true),
        )

        runScenario("warmup", ENTRY_COUNT)
        val measured = List(MEASURED_RUNS) { index ->
            runScenario("measured-$index", ENTRY_COUNT)
        }
        val medians = BatchMetrics(
            createVaultMs = measured.map(BatchMetrics::createVaultMs).median(),
            batchCreateMs = measured.map(BatchMetrics::batchCreateMs).median(),
            coldReadMs = measured.map(BatchMetrics::coldReadMs).median(),
            hotReadMs = measured.map(BatchMetrics::hotReadMs).median(),
            batchUpdateMs = measured.map(BatchMetrics::batchUpdateMs).median(),
            batchDeleteMs = measured.map(BatchMetrics::batchDeleteMs).median(),
        )
        val output = buildString {
            append("MDBX2_MONICA_REPOSITORY_BATCH_PERF ")
            append("{\"entries\":$ENTRY_COUNT")
            append(",\"runs\":$MEASURED_RUNS")
            append(",\"abi\":\"${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}\"")
            append(",\"sdk\":${Build.VERSION.SDK_INT}")
            append(",\"create_vault_ms\":${medians.createVaultMs}")
            append(",\"batch_create_ms\":${medians.batchCreateMs}")
            append(",\"cold_read_ms\":${medians.coldReadMs}")
            append(",\"hot_read_ms\":${medians.hotReadMs}")
            append(",\"batch_update_ms\":${medians.batchUpdateMs}")
            append(",\"batch_delete_ms\":${medians.batchDeleteMs}}")
        }
        Log.i(LOG_TAG, output)
        println(output)
    }

    @Test
    fun repositoryBatchExceedsInteractiveCommandLimit(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Manual command-limit test; pass -e mdbxBatchLimit true",
            arguments.getString(BATCH_LIMIT_ARGUMENT).equals("true", ignoreCase = true),
        )

        runScenario("command-limit", COMMAND_LIMIT_ENTRY_COUNT)
    }

    private suspend fun runScenario(label: String, entryCount: Int): BatchMetrics {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "mdbx2-repository-perf-$label"
        val vaultCreationStarted = SystemClock.elapsedRealtimeNanos()
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val createVaultMs =
            (SystemClock.elapsedRealtimeNanos() - vaultCreationStarted) / 1_000_000
        var databaseId = 0L

        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "MDBX2 performance $label",
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
                    isOfflineAvailable = true,
                    lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name,
                )
            )
            val entries = List(entryCount) { index ->
                PasswordEntry(
                    id = PERFORMANCE_ID_BASE + index,
                    title = "Entry ${index.toString().padStart(3, '0')}",
                    website = "https://performance-$index.example",
                    username = "user-${index.toString().padStart(3, '0')}",
                    password = "secret-${index.toString().padStart(3, '0')}",
                    notes = "MDBX2 repository benchmark",
                    mdbxDatabaseId = databaseId,
                    mdbxFolderId = null,
                )
            }

            val batchCreate = timedValue { repository.upsertPasswords(entries) }
            val coldRead = timedValue { repository.readStoredEntries(databaseId) }
            assertEquals(entryCount, coldRead.value.count { !it.deleted })
            val hotRead = timedValue { repository.readStoredEntries(databaseId) }
            assertEquals(entryCount, hotRead.value.count { !it.deleted })

            val updatedEntries = entries.mapIndexed { index, entry ->
                entry.copy(
                    title = "Updated entry ${index.toString().padStart(3, '0')}",
                    username = "updated-user-${index.toString().padStart(3, '0')}",
                )
            }
            val batchUpdate = timedValue { repository.upsertPasswords(updatedEntries) }
            assertEquals(
                entryCount,
                repository.readStoredEntries(databaseId).count { !it.deleted },
            )

            val batchDelete = timedValue { repository.deletePasswords(updatedEntries) }
            val deleted = repository.readStoredEntries(databaseId)
            assertEquals(entryCount, deleted.size)
            assertTrue(deleted.all { it.deleted })

            return BatchMetrics(
                createVaultMs = createVaultMs,
                batchCreateMs = batchCreate.elapsedMs,
                coldReadMs = coldRead.elapsedMs,
                hotReadMs = hotRead.elapsedMs,
                batchUpdateMs = batchUpdate.elapsedMs,
                batchDeleteMs = batchDelete.elapsedMs,
            )
        } finally {
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(vaultFile)
        }
    }

    private suspend inline fun <T> timedValue(crossinline block: suspend () -> T): TimedValue<T> {
        val started = SystemClock.elapsedRealtimeNanos()
        val value = block()
        return TimedValue(
            value = value,
            elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000,
        )
    }

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private data class TimedValue<T>(
        val value: T,
        val elapsedMs: Long,
    )

    private data class BatchMetrics(
        val createVaultMs: Long,
        val batchCreateMs: Long,
        val coldReadMs: Long,
        val hotReadMs: Long,
        val batchUpdateMs: Long,
        val batchDeleteMs: Long,
    )

    private companion object {
        const val ENTRY_COUNT = 200
        const val COMMAND_LIMIT_ENTRY_COUNT = 300
        const val MEASURED_RUNS = 3
        const val PERFORMANCE_ID_BASE = 9_100_000L
        const val PERF_ARGUMENT = "mdbxPerf"
        const val BATCH_LIMIT_ARGUMENT = "mdbxBatchLimit"
        const val LOG_TAG = "Mdbx2MonicaPerf"
    }
}
