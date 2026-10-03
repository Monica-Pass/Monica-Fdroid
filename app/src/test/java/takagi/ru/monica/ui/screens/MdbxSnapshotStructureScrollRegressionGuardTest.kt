package takagi.ru.monica.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MdbxSnapshotStructureScrollRegressionGuardTest {

    @Test
    fun portraitSnapshotStructureHasBoundedVerticalScrolling() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()
        val pageBody = source
            .substringAfter("private fun MdbxSnapshotStructurePage(")
            .substringBefore("internal fun SnapshotStructurePreviewPage(")
        val browser = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/MdbxFolderBrowser.kt").readText()
        val preview = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/MdbxSnapshotBrowser.kt").readText()

        assertTrue(
            "Snapshot preview must receive a bounded remaining height below the optional loading indicator.",
            pageBody.contains("modifier = Modifier.weight(1f)")
        )
        assertTrue(
            "Each folder pane must retain a bounded lazy list, not an eagerly expanded tree.",
            browser.contains("Box(Modifier.weight(1f))") && browser.contains("LazyColumn(")
        )
        assertTrue("Comparison must use independently scrollable panes", preview.contains("pane(true, Modifier.weight(1f))") &&
            preview.contains("pane(false, Modifier.weight(1f))") && !preview.contains(".verticalScroll("))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            candidates += File(directory, relativePath)
            directory = directory.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath")
    }
}
