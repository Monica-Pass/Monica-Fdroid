package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupCompactUiGuardTest {
    @Test
    fun `main page keeps unbounded content out of the root list`() {
        val screen = source()
        val rootScreen = screen
            .substringAfter("fun DedupEngineScreen(")
            .substringBefore("private fun CompactSetupPanel(")

        assertTrue(rootScreen.contains("CompactSetupPanel("))
        assertTrue(rootScreen.contains("CompactLinkPanel("))
        assertFalse(rootScreen.contains("PasswordPreviewRow("))
        assertFalse(rootScreen.contains("SecureItemPreviewRow("))
        assertFalse(rootScreen.contains("items(uiState.sourceOptions"))
        assertFalse(rootScreen.contains("items(uiState.targetOptions"))
    }

    @Test
    fun `database and detail sheets use lazy lists`() {
        val screen = source()

        listOf(
            "SourceSelectionSheet",
            "TargetSelectionSheet",
            "MergePreviewSheet",
            "WarningSheet",
            "FailureSheet"
        ).forEach { functionName ->
            val functionBody = screen
                .substringAfter("private fun $functionName(")
                .substringBefore("\n@")
            assertTrue("$functionName must use LazyColumn", functionBody.contains("LazyColumn("))
        }
        assertFalse(screen.contains("showAllPreview"))
        assertFalse(screen.contains("take(80)"))
    }

    private fun source(): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/takagi/ru/monica/ui/screens/DedupEngineScreen.kt"
        ).readText()
    }
}
