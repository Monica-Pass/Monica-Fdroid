package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.NativeApiTokenUpload
import uniffi.mdbx_ffi.MdbxTigaMode
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.openVault

/** Exercises the exact direct-native editor path without claiming emulator hardware assurance. */
@RunWith(AndroidJUnit4::class)
class MdbxNativeCredentialInstrumentedTest {
    @Test fun credentialAndAttachmentCreateEditReopenReadDeleteStayNative() = runBlocking {
        Mdbx2NativeRuntime.ensureLoaded()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "native-credential-${UUID.randomUUID()}.mdbx")
        val bytes = "private attachment 内容".toByteArray()
        val password = "native-credential-test-password"
        val id: String
        try {
            val vault = createVaultWithTigaMode(file.absolutePath, password, "android-native-editor", MdbxTigaMode.SKY)
            try {
                id = saveMdbxNativeObject(vault, null, "Glitter workflow marker", "login",
                    """{"username":"alice","password_plain":"initial-secret","unknown":{"large":9007199254740993}}""",
                    listOf(NativeApiTokenUpload("private.txt", "text/plain") { bytes.inputStream() }), emptySet())
                val first = readMdbxNativeObject(vault, id)
                assertEquals(1, first.attachmentRecords.size)
                assertArrayEquals(bytes, readMdbxNativeAttachment(vault, first, first.attachmentRecords.single().id))
                saveMdbxNativeObject(vault, first, "Glitter edited marker", "login",
                    first.payload.replace("initial-secret", "updated-secret"), emptyList(), emptySet())
                assertTrue(runCatching { saveMdbxNativeObject(vault, first, "stale overwrite", "login", first.payload, emptyList(), emptySet()) }.isFailure)
            } finally { vault.close() }
            val reopened = openVault(file.absolutePath, password, "android-native-editor-reopen")
            try {
                val browser = readMdbxNativeBrowser(reopened)
                assertEquals("Glitter edited marker", browser.objects.getValue(id).title)
                val current = readMdbxNativeObject(reopened, id)
                assertTrue(current.payload.contains("updated-secret"))
                assertTrue(current.payload.contains("9007199254740993"))
                assertArrayEquals(bytes, readMdbxNativeAttachment(reopened, current, current.attachmentRecords.single().id))
                deleteMdbxNativeObject(reopened, current)
                assertFalse(readMdbxNativeBrowser(reopened).objects.containsKey(id))
                assertTrue(reopened.listAttachments(current.summary.collectionId, id).none { !it.deleted })
            } finally { reopened.close() }
        } finally {
            bytes.fill(0)
            listOf(file, File(file.absolutePath + "-wal"), File(file.absolutePath + "-shm")).forEach { it.delete() }
        }
    }
}
