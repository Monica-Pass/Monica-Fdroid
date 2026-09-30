package takagi.ru.monica.credentialexchange

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.utils.WebDavHelper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NativeTokenArchiveManifestInstrumentedTest {
    @Test fun missingOrEmptyNativeTokenFileFailsParsingAndLeavesExistingRecordsUntouched() = runBlocking {
        val fixture = TransferFixture()
        try {
            val original = PasswordEntry(title = "${fixture.prefix}-manifest-original", username = "alice",
                password = "original-secret", website = "", notes = "keep original")
            val id = fixture.db.passwordEntryDao().insertPasswordEntry(original)
            val reader = WebDavHelper(fixture.context)
            for (tokenFile in listOf<String?>(null, "native_api_tokens.json", "nested/native_api_tokens.json")) {
                val archive = File.createTempFile("native-token-manifest-", ".zip", fixture.context.cacheDir)
                try {
                    ZipOutputStream(archive.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("database_export.json"))
                        zip.write("""{"version":1,"source":"MDBX","nativeTokenCount":1}""".toByteArray())
                        zip.closeEntry()
                        if (tokenFile != null) {
                            zip.putNextEntry(ZipEntry(tokenFile)); zip.write("[]".toByteArray()); zip.closeEntry()
                        }
                    }
                    val result = reader.restoreFromBackupFile(archive, restoreMonicaConfig = false, importDataOnly = true)
                    assertTrue("Missing, empty or misplaced token list must reject a counted archive", result.isFailure)
                    val current = requireNotNull(fixture.db.passwordEntryDao().getPasswordEntryById(id))
                    assertEquals(original.password, current.password)
                    assertEquals(original.notes, current.notes)
                } finally { archive.delete() }
            }
        } finally { fixture.close() }
    }

    @Test fun legacyManifestWithoutNativeTokenCountStillParses() = runBlocking {
        val fixture = TransferFixture()
        val archive = File.createTempFile("legacy-native-token-", ".zip", fixture.context.cacheDir)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("database_export.json"))
                zip.write("""{"version":1,"source":"MDBX"}""".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("native_api_tokens.json"))
                zip.write("""[{"title":"Legacy token","payload":"opaque","metadata":"{\"schema\":\"monica.api-token.fields.v1\"}"}]""".toByteArray())
                zip.closeEntry()
            }
            val result = WebDavHelper(fixture.context).restoreFromBackupFile(archive,
                restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
            assertEquals("Legacy token", result.content.nativeTokens.single().title)
            assertTrue(result.report.failedItems.isEmpty())
        } finally { archive.delete(); fixture.close() }
    }
}
