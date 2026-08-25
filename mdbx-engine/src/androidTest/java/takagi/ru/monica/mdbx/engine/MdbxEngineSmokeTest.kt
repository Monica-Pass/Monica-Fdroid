package takagi.ru.monica.mdbx.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import uniffi.mdbx_ffi.createVault
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.MdbxTigaMode
import uniffi.mdbx_ffi.openVault

@RunWith(AndroidJUnit4::class)
class MdbxEngineSmokeTest {

    @Test
    fun skyVaultReopensAfterClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDirectory = File(context.cacheDir, "mdbx2-sky-${UUID.randomUUID()}")
        assertTrue(testDirectory.mkdirs())
        val vaultFile = File(testDirectory, "sky.mdbx")
        val password = "mdbx2-sky-password"
        val deviceId = "android-sky-device"

        try {
            val vaultId = createVaultWithTigaMode(
                path = vaultFile.absolutePath,
                password = password,
                deviceId = deviceId,
                mode = MdbxTigaMode.SKY,
            ).use { created ->
                created.info().vaultId
            }

            openVault(vaultFile.absolutePath, password, deviceId).use { reopened ->
                assertEquals(vaultId, reopened.info().vaultId)
            }
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    @Test
    fun explicitMonicaEntryIdSurvivesUpdateDeleteAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDirectory = File(context.cacheDir, "mdbx2-explicit-${UUID.randomUUID()}")
        assertTrue(testDirectory.mkdirs())
        val vaultFile = File(testDirectory, "explicit.mdbx")
        val password = "mdbx2-explicit-password"
        val deviceId = "android-explicit-device"
        val projectId = UUID.randomUUID().toString()
        val logicalEntryId = "password:42"
        val entryId = UUID.nameUUIDFromBytes(
            "monica-entry:test-vault:$logicalEntryId".toByteArray(Charsets.UTF_8)
        ).toString()

        try {
            createVault(vaultFile.absolutePath, password, deviceId).use { vault ->
                vault.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "test-create",
                    commands = listOf(
                        MdbxWriteCommand.CreateProject(projectId, "Personal"),
                        MdbxWriteCommand.CreateEntry(
                            entryId = entryId,
                            projectId = projectId,
                            entryType = "login",
                            title = "Initial",
                            payloadJson = """{"kind":"password","monica_entry_id":"$logicalEntryId","username":"alice"}"""
                        )
                    )
                )
                assertEquals(entryId, vault.listEntries(projectId, "login").single().entryId)

                vault.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "test-update-delete",
                    commands = listOf(
                        MdbxWriteCommand.UpdateEntry(
                            entryId = entryId,
                            projectId = projectId,
                            entryType = "login",
                            title = "Updated",
                            payloadJson = """{"kind":"password","monica_entry_id":"$logicalEntryId","username":"bob"}"""
                        ),
                        MdbxWriteCommand.DeleteEntry(entryId, projectId)
                    )
                )
            }

            openVault(vaultFile.absolutePath, password, deviceId).use { reopened ->
                assertTrue(reopened.listEntries(projectId, null).isEmpty())
                val deleted = reopened.listDeletedEntries(projectId, "login").single()
                assertEquals(entryId, deleted.entryId)
                assertEquals("Updated", deleted.title)
                assertTrue(deleted.deleted)
            }
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    @Test
    fun createsWritesReadsAndReopensVault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDirectory = File(context.cacheDir, "mdbx2-smoke-${UUID.randomUUID()}")
        assertTrue(testDirectory.mkdirs())

        val vaultFile = File(testDirectory, "smoke.mdbx")
        val password = "mdbx2-smoke-password"
        val deviceId = "android-smoke-device"
        val payload =
            """{"kind":"password","username":"alice","password":"secret","favorite":false}"""

        try {
            val projectId = createAndReadEntry(
                path = vaultFile.absolutePath,
                password = password,
                deviceId = deviceId,
                payload = payload,
            )

            assertTrue(vaultFile.isFile)

            val reopened = openVault(vaultFile.absolutePath, password, deviceId)
            try {
                val persistedEntries = reopened.listEntries(projectId, "login")
                assertEquals(1, persistedEntries.size)
                assertEquals("GitHub Login", persistedEntries.single().title)
                assertJsonEquivalent(payload, persistedEntries.single().payloadJson)
                assertFalse(persistedEntries.single().deleted)
            } finally {
                reopened.close()
            }
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    private fun createAndReadEntry(
        path: String,
        password: String,
        deviceId: String,
        payload: String,
    ): String {
        val vault = createVault(path, password, deviceId)
        try {
            val project = vault.createProject("Personal")
            val created = vault.createEntry(
                projectId = project.projectId,
                entryType = "login",
                title = "GitHub Login",
                payloadJson = payload,
            )

            assertEquals(project.projectId, created.projectId)
            assertEquals("login", created.entryType)
            assertEquals("GitHub Login", created.title)
            assertJsonEquivalent(payload, created.payloadJson)
            assertFalse(created.deleted)

            val firstRead = vault.listEntries(project.projectId, "login")
            assertEquals(1, firstRead.size)
            assertEquals(created.entryId, firstRead.single().entryId)

            return project.projectId
        } finally {
            vault.close()
        }
    }

    private fun assertJsonEquivalent(expected: String, actual: String) {
        assertEquals(
            canonicalizeJson(JSONTokener(expected).nextValue()),
            canonicalizeJson(JSONTokener(actual).nextValue()),
        )
    }

    private fun canonicalizeJson(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { key ->
            canonicalizeJson(value.get(key))
        }
        is JSONArray -> (0 until value.length()).map { index ->
            canonicalizeJson(value.get(index))
        }
        JSONObject.NULL -> null
        else -> value
    }
}
