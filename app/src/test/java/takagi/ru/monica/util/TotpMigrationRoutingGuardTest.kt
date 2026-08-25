package takagi.ru.monica.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpMigrationRoutingGuardTest {

    @Test
    fun `migration support stays isolated from ordinary camera Steam and FIDO scanners`() {
        val parser = projectFile(
            "app/src/main/java/takagi/ru/monica/util/TotpUriParser.kt"
        ).readText()
        val scanner = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/QrScannerScreen.kt"
        ).readText()
        val mainActivity = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()
        val totpEditor = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditTotpScreen.kt"
        ).readText()
        val appBuild = projectFile("app/build.gradle").readText()

        assertTrue(parser.contains("lower.startsWith(\"otpauth://\") || lower.startsWith(\"motp://\")"))
        assertTrue(parser.contains("lower.startsWith(\"phonefactor://\")"))
        assertTrue(parser.contains("private fun parseOtpAuthUri(uri: String)"))
        assertFalse(scanner.contains("otpauth-migration"))
        assertTrue(mainActivity.contains("Screen.SteamQrScan.route"))
        assertTrue(mainActivity.contains("Screen.FidoQrScan.route"))
        assertTrue(mainActivity.contains("TotpMigrationReviewDialog"))
        assertTrue(mainActivity.contains("return@onQrCodeScanned"))
        assertTrue(mainActivity.contains("saveTotpMigrationBatch"))
        assertTrue(totpEditor.contains("TotpMigrationReviewDialog"))
        assertTrue(totpEditor.contains("onBatchImport(selectedItems"))
        assertFalse(appBuild.contains("protobuf", ignoreCase = true))
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }
}
