package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKeePassExportRegressionGuardTest {
    @Test
    fun internalDatabaseExportStaysConnectedToDocumentPickerAndViewModel() {
        val screen = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/LocalKeePassScreen.kt"
        ).readText()

        assertTrue(screen.contains("var databaseToExport"))
        assertTrue(screen.contains("val exportToExternalLauncher"))
        assertTrue(screen.contains("viewModel.exportToExternal(db.id, targetUri)"))
        assertTrue(screen.contains("onExport = { db ->"))
        assertTrue(screen.contains("exportToExternalLauncher.launch"))
        assertFalse(screen.contains("onExport = { /* 需要文件选择器 */ }"))
    }

    @Test
    fun exportReportsAnErrorWhenTheDestinationCannotBeOpened() {
        val viewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/LocalKeePassViewModel.kt"
        ).readText()

        assertTrue(viewModel.contains("openOutputStream(destinationUri)"))
        assertTrue(viewModel.contains("?: throw IOException(\"无法打开目标文件\")"))
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
