package takagi.ru.monica.mdbx.engine

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.createVault
import uniffi.mdbx_ffi.openVault

@RunWith(AndroidJUnit4::class)
class MdbxEngineBatchPerformanceTest {

    @Test
    fun nativeBindingBatchPerformanceBaseline() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Manual performance test; pass -e mdbxPerf true",
            arguments.getString(PERF_ARGUMENT).equals("true", ignoreCase = true),
        )

        runScenario("warmup")
        val measured = List(MEASURED_RUNS) { index ->
            runScenario("measured-$index")
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
            append("MDBX2_ANDROID_NATIVE_BATCH_PERF ")
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

    private fun runScenario(label: String): BatchMetrics {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDirectory = File(context.cacheDir, "mdbx2-batch-perf-${UUID.randomUUID()}")
        assertTrue(testDirectory.mkdirs())
        val vaultFile = File(testDirectory, "batch-performance.mdbx")
        val password = "MDBX2 Android batch performance password 12345!"
        val deviceId = "android-batch-performance-$label"

        try {
            var vault = timedValue {
                createVault(vaultFile.absolutePath, password, deviceId)
            }
            val createVaultMs = vault.elapsedMs
            val project = vault.value.createProject("Performance project $label")
            val entryIds = List(ENTRY_COUNT) { UUID.randomUUID().toString() }

            val createCommands = entryIds.mapIndexed { index, entryId ->
                MdbxWriteCommand.CreateEntry(
                    entryId = entryId,
                    projectId = project.projectId,
                    entryType = "login",
                    title = "Entry ${index.toString().padStart(3, '0')}",
                    payloadJson = payload(index, revision = 0),
                )
            }
            val batchCreate = timedValue {
                vault.value.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "android-native-batch-create",
                    commands = createCommands,
                )
            }
            assertEquals(ENTRY_COUNT, batchCreate.value.entryIds.size)

            vault.value.close()
            vault = TimedValue(
                value = openVault(vaultFile.absolutePath, password, deviceId),
                elapsedMs = 0,
            )

            val coldRead = timedValue {
                vault.value.listEntries(project.projectId, "login")
            }
            assertEquals(ENTRY_COUNT, coldRead.value.size)
            val hotRead = timedValue {
                vault.value.listEntries(project.projectId, "login")
            }
            assertEquals(ENTRY_COUNT, hotRead.value.size)

            val updateCommands = entryIds.mapIndexed { index, entryId ->
                MdbxWriteCommand.UpdateEntry(
                    entryId = entryId,
                    projectId = project.projectId,
                    entryType = "login",
                    title = "Updated entry ${index.toString().padStart(3, '0')}",
                    payloadJson = payload(index, revision = 1),
                )
            }
            val batchUpdate = timedValue {
                vault.value.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "android-native-batch-update",
                    commands = updateCommands,
                )
            }
            assertEquals(ENTRY_COUNT, batchUpdate.value.entryIds.size)

            val deleteCommands = entryIds.map { entryId ->
                MdbxWriteCommand.DeleteEntry(
                    entryId = entryId,
                    projectId = project.projectId,
                )
            }
            val batchDelete = timedValue {
                vault.value.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "android-native-batch-delete",
                    commands = deleteCommands,
                )
            }
            assertEquals(ENTRY_COUNT, batchDelete.value.entryIds.size)
            assertTrue(vault.value.listEntries(project.projectId, "login").isEmpty())
            vault.value.close()

            return BatchMetrics(
                createVaultMs = createVaultMs,
                batchCreateMs = batchCreate.elapsedMs,
                coldReadMs = coldRead.elapsedMs,
                hotReadMs = hotRead.elapsedMs,
                batchUpdateMs = batchUpdate.elapsedMs,
                batchDeleteMs = batchDelete.elapsedMs,
            )
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    private fun payload(index: Int, revision: Int): String =
        """{"username":"user-${index.toString().padStart(3, '0')}","password":"secret-${index.toString().padStart(3, '0')}","revision":$revision}"""

    private inline fun <T> timedValue(block: () -> T): TimedValue<T> {
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
        const val MEASURED_RUNS = 3
        const val PERF_ARGUMENT = "mdbxPerf"
        const val LOG_TAG = "Mdbx2BatchPerf"
    }
}
