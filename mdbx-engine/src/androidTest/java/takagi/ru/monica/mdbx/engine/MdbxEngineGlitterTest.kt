package takagi.ru.monica.mdbx.engine

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.mdbx_ffi.*

/** Real JNI/database/Argon2 tests using password + key and a normal device context. */
@RunWith(AndroidJUnit4::class)
class MdbxEngineGlitterTest {
    private val evidence = MdbxDeviceContext(MdbxDeviceAssurance.STANDARD, false, false, false)
    private val password = "Synthetic native Glitter password 2026!"
    private val factor = ByteArray(32) { (it + 17).toByte() }
    private val device = "glitter-instrumentation-synthetic"

    @Test
    fun realGlitterJniRoundtripCachesOnlyTitlesAndPreservesUpdates() {
        val dir = directory()
        val file = File(dir, "synthetic.mdbx")
        val ids = List(256) { UUID.randomUUID().toString() }
        val project = UUID.randomUUID().toString()
        try {
            val start = SystemClock.elapsedRealtime()
            createVaultWithPasswordSecurityKey(file.path, password, factor, device, MdbxTigaMode.GLITTER, evidence).use { vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "glitter-test-collection",
                    listOf(MdbxWriteCommand.CreateProject(project, "Synthetic collection")))
                vault.executeWriteOperation(UUID.randomUUID().toString(), "glitter-test-create",
                    ids.mapIndexed { index, id ->
                        MdbxWriteCommand.CreateEntry(id, project, "login", "Synthetic $index",
                            """{"username":"synthetic","password":"not-a-real-secret"}""")
                    })
            }
            val createMs = SystemClock.elapsedRealtime() - start
            val openStart = SystemClock.elapsedRealtime()
            openVaultWithPasswordSecurityKeyAndDeviceContext(file.path, password, factor, device, evidence).use { vault ->
                val unlockMs = SystemClock.elapsedRealtime() - openStart
                repeat(3) {
                    assertEquals(256, summaries(vault, project).size)
                }
                val cache = vault.metadataCacheStats()
                assertTrue(cache.hits > 0uL)
                assertTrue(cache.entries >= 256uL)
                assertTrue(cache.retainedBytes <= cache.byteLimit)
                expectRejected { vault.listEntries(project, "login") }
                expectRejected { vault.getObject(project, ids.first()) }
                val revealed = vault.revealObject(ids.first()).`object`
                assertNotNull(revealed)
                assertTrue(revealed!!.payloadJson.contains("not-a-real-secret"))
                vault.executeWriteOperation(UUID.randomUUID().toString(), "glitter-test-update", listOf(
                    MdbxWriteCommand.UpdateEntry(ids.first(), project, "login", "Updated synthetic",
                        """{"password":"updated-synthetic"}""")
                ))
                assertEquals("Updated synthetic", vault.getObjectSummary(ids.first())!!.title)
                assertTrue(vault.revealObject(ids.first()).`object`!!.payloadJson.contains("updated-synthetic"))
                Log.i("GlitterNativeTest", "portable=true entries=256 create_ms=$createMs unlock_ms=$unlockMs cache_hits=${cache.hits}")
            }
            expectRejected { openVault(file.path, password, device).close() }
            openVaultWithPasswordSecurityKey(file.path, password, factor, "another-device").use { vault ->
                assertEquals("Updated synthetic", vault.getObjectSummary(ids.first())!!.title)
            }
            expectRejected { openVaultWithSecurityKey(file.path, factor, device).close() }
            expectRejected { openVaultWithPasswordSecurityKey(file.path, password, ByteArray(32) { 1 }, device).close() }
            expectRejected {
                openVaultWithPasswordSecurityKeyAndDeviceContext(file.path, "wrong synthetic password", factor, device, evidence).close()
            }
            openVaultWithPasswordSecurityKeyAndDeviceContext(file.path, password, factor, device, evidence).use { vault ->
                assertEquals("Updated synthetic", vault.getObjectSummary(ids.first())!!.title)
                vault.executeWriteOperation(UUID.randomUUID().toString(), "glitter-test-delete",
                    listOf(MdbxWriteCommand.DeleteEntry(ids.first(), project)))
                assertTrue(summaries(vault, project).none { it.objectId == ids.first() })
            }
        } finally {
            check(requireNotNull(dir.parentFile).canonicalFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
            dir.deleteRecursively()
        }
    }

    @Test
    fun glitterRejectsMissingOrShortFactorsBeforeCreatingAFile() {
        val dir = directory()
        try {
            val file = File(dir, "rejected.mdbx")
            expectRejected { createVaultWithTigaMode(file.path, password, device, MdbxTigaMode.GLITTER).close() }
            assertFalse(file.exists())
            expectRejected { createVaultWithPasswordSecurityKey(file.path, password, ByteArray(31), device,
                MdbxTigaMode.GLITTER, evidence).close() }
            assertFalse(file.exists())
            expectRejected { createVaultWithPasswordSecurityKey(file.path, "", factor, device,
                MdbxTigaMode.GLITTER, evidence).close() }
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }

    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "glitter-engine-${UUID.randomUUID()}").also { assertTrue(it.mkdir()) }

    private fun summaries(vault: MdbxVault, project: String): List<MdbxObjectSummary> {
        val results = mutableListOf<MdbxObjectSummary>()
        var cursor: String? = null
        do {
            val page = vault.listObjectSummaries(project, "login", 200u, cursor)
            results.addAll(page.items)
            cursor = page.nextCursor
        } while (cursor != null)
        return results
    }

    private fun expectRejected(block: () -> Unit) {
        try { block() } catch (_: MdbxFfiException) { return }
        fail("Expected native security rejection")
    }
}
