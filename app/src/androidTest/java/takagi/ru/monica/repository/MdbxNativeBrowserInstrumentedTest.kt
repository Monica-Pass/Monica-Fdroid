package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.mdbx_ffi.*

@RunWith(AndroidJUnit4::class)
class MdbxNativeBrowserInstrumentedTest {
    @Test fun nativeManagementPreservesUnknownVersionsPayloadsAndAttachmentsAfterReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val work = File(context.cacheDir, "native-browser-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            ZipInputStream(instrumentation.context.assets.open("mdbx-cli-contract-v1.zip")).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(work, entry.name).canonicalFile
                    check(target.path.startsWith(work.canonicalPath + File.separator))
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile!!.mkdirs()
                        target.outputStream().use { output ->
                            val bytes = ByteArray(8192)
                            while (true) {
                                val n = zip.read(bytes); if (n < 0) break
                                total += n; check(total < 16L * 1024 * 1024)
                                output.write(bytes, 0, n)
                            }
                        }
                    }
                }
            }
            val manifest = Json.parseToJsonElement(File(work, "manifest.json").readText()).jsonObject
            val password = manifest.getValue("password").jsonPrimitive.content
            check(password == "Synthetic cross-client contract 20260928!")
            val parent = manifest.getValue("parent").jsonPrimitive.content
            val child = manifest.getValue("child").jsonPrimitive.content
            val attachment = manifest.getValue("attachment").jsonObject
            val attachmentId = attachment.getValue("id").jsonPrimitive.content
            val expectedBytes = attachment.getValue("bytes").jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
            Mdbx2NativeRuntime.ensureLoaded()
            val path = File(work, "fixture.mdbx").absolutePath
            val original = mutableMapOf<String, MdbxNativeObjectDetail>()
            openVault(path, password, "native-manager-test").use { vault ->
                // Every supported category plus literal nested values that must never be flattened.
                val types = listOf("login", "totp", "bankcard", "document", "note", "passkey", "wifi", "ssh-key", "api-key", "api-token", "gpg-key", "barcode", "steam")
                vault.executeWriteOperation(UUID.randomUUID().toString(), "test-fixtures", types.map { type ->
                    MdbxWriteCommand.CreateEntry(UUID.randomUUID().toString(), child, type, "Synthetic $type",
                        """{"type":"$type","credentials":[{"username":"one","passwords":["p1","p2"]},{"username":"two","otp":"SYNTHETIC"}],"unknown":{"number":1.234567890123456789,"bool":false,"null":null,"nested":[{},[]]}}""")
                })
                val browser = readMdbxNativeBrowser(vault)
                assertTrue(browser.objects.values.map { it.type }.containsAll(types))
                browser.objects.values.forEach { summary ->
                    original[summary.id] = readMdbxNativeObject(vault, summary.id)
                }
                val beforeRead = vault.info().toString()
                repeat(3) { assertEquals(browser, readMdbxNativeBrowser(vault)) }
                assertEquals("Browsing must not change commits", beforeRead, vault.info().toString())
                original.values.forEach { entry ->
                    renameMdbxNativeObject(vault, entry.summary, "Renamed ${entry.summary.title}")
                    val renamed = readMdbxNativeObject(vault, entry.summary.id)
                    assertEquals(entry.summary.version, renamed.summary.version)
                    assertEquals(Json.parseToJsonElement(entry.payload), Json.parseToJsonElement(renamed.payload))
                    assertEquals(entry.attachments, renamed.attachments)
                    assertTrue(runCatching { renameMdbxNativeObject(vault, entry.summary, "Stale overwrite") }.isFailure)
                }
                assertArrayEquals(expectedBytes, vault.readAttachmentContent(attachmentId, 1024uL * 1024uL))
                // Folder operations change hierarchy only; contents and ownership remain intact.
                val newFolder = UUID.randomUUID().toString()
                vault.executeWriteOperation(UUID.randomUUID().toString(), "native-folder-test", listOf(
                    MdbxWriteCommand.CreateProjectWithParent(newFolder, "New parent", null),
                    MdbxWriteCommand.RenameProject(parent, "Renamed folder"),
                    MdbxWriteCommand.MoveProject(parent, newFolder)))
                val index = MdbxBrowserIndex(readMdbxNativeBrowser(vault).nodes)
                assertEquals(listOf(newFolder, parent, child), index.ancestors(child).map { it.id })
            }
            openVault(path, password, "independent-reader").use { vault ->
                original.forEach { (id, entry) ->
                    val after = readMdbxNativeObject(vault, id)
                    assertEquals("Renamed ${entry.summary.title}", after.summary.title)
                    assertEquals(entry.summary.version, after.summary.version)
                    assertEquals(entry.summary.collectionId, after.summary.collectionId)
                    assertEquals(entry.summary.type, after.summary.type)
                    assertEquals(Json.parseToJsonElement(entry.payload), Json.parseToJsonElement(after.payload))
                }
                assertArrayEquals(expectedBytes, vault.readAttachmentContent(attachmentId, 1024uL * 1024uL))
            }
        } finally { check(work.deleteRecursively()) }
    }
}
