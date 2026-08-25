package takagi.ru.monica.external

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTotpImportControllerTest {

    @Test
    fun `validated URI is queued once and matching consume clears it`() {
        val controller = ExternalTotpImportController(uriValidator = { true })
        val uri = "otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP&issuer=Example"

        assertTrue(controller.offerUri(uri))
        assertNotNull(controller.pendingRequest.value)
        val first = requireNotNull(controller.pendingRequest.value)
        assertEquals(uri, first.uri)

        controller.consume(first.id + 1L)
        assertNotNull(controller.pendingRequest.value)
        controller.consume(first.id)
        assertNull(controller.pendingRequest.value)
    }

    @Test
    fun `repeated URI creates a new request identity`() {
        val controller = ExternalTotpImportController(uriValidator = { true })
        val uri = "otpauth://hotp/Example:user?secret=JBSWY3DPEHPK3PXP&counter=1"

        assertTrue(controller.offerUri(uri))
        val firstId = requireNotNull(controller.pendingRequest.value).id
        assertTrue(controller.offerUri(uri))
        val secondId = requireNotNull(controller.pendingRequest.value).id

        assertTrue(secondId > firstId)
    }

    @Test
    fun `unsupported scheme blank and oversized values are rejected before validation`() {
        var validationCalls = 0
        val controller = ExternalTotpImportController(uriValidator = {
            validationCalls += 1
            true
        })

        assertFalse(controller.offerUri("https://example.com"))
        assertFalse(controller.offerUri(""))
        assertFalse(controller.offerUri("otpauth://totp/" + "a".repeat(16_384)))
        assertEquals(0, validationCalls)
        assertNull(controller.pendingRequest.value)
    }

    @Test
    fun `manifest and main activity keep external secret in memory only`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val mainActivity = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()

        assertTrue(manifest.contains("android:scheme=\"otpauth\""))
        assertTrue(manifest.contains("android:host=\"totp\""))
        assertTrue(manifest.contains("android:host=\"hotp\""))
        assertTrue(manifest.contains("android:host=\"yaotp\""))
        assertFalse(manifest.contains("android:scheme=\"otpauth-migration\""))
        assertTrue(mainActivity.contains("override fun onNewIntent(intent: Intent)"))
        assertTrue(mainActivity.contains("ExternalTotpImportController"))
        assertTrue(mainActivity.contains("pendingExternalTotpImport"))
        assertTrue(mainActivity.contains("totpId == 0L"))
        assertFalse(mainActivity.contains("Log.d(TAG, pendingExternalTotpImport"))
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
