package takagi.ru.monica.autofill_ng

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.ui.screens.buildDeveloperLogShareFallback

class AutofillDiagnosticsShareTest {

    @Test
    fun shortReportRemainsAvailableAsShareText() {
        val report = "=== Monica Developer Log Report ===\nERROR field mapping"

        assertEquals(report, buildDeveloperLogShareFallback(report))
    }

    @Test
    fun longReportKeepsHeaderAndLatestEntriesWithinLimit() {
        val report = "HEADER\n" + "x".repeat(60_000) + "\nLATEST_ERROR"
        val shared = buildDeveloperLogShareFallback(report, maxChars = 12_000)

        assertTrue(shared.length <= 12_000)
        assertTrue(shared.startsWith("HEADER"))
        assertTrue(shared.endsWith("LATEST_ERROR"))
        assertTrue(shared.contains("Share text truncated"))
    }

    @Test
    fun shareIntentProvidesBothStreamPermissionAndTextFallback() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/DeveloperSettingsScreen.kt"
        ).readText()

        assertTrue(source.contains("putExtra(Intent.EXTRA_STREAM, uri)"))
        assertTrue(source.contains("ClipData.newRawUri(fileName, uri)"))
        assertTrue(source.contains("putExtra(Intent.EXTRA_TEXT, buildDeveloperLogShareFallback(report))"))
        assertTrue(source.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
    }

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
