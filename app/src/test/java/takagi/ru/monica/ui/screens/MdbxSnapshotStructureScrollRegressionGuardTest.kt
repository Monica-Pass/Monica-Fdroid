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
            .substringBefore("@Composable\nprivate fun SnapshotStructurePreviewPage(")
        val previewBody = source
            .substringAfter("private fun SnapshotStructurePreviewPage(")
            .substringBefore("@Composable\nprivate fun StructureTreePanel(")
        val portraitBranch = previewBody.substringAfter("        } else {")

        assertTrue(
            "Snapshot preview must receive a bounded remaining height below the optional loading indicator.",
            pageBody.contains("modifier = Modifier.weight(1f)")
        )
        assertTrue(
            "Portrait snapshot structure must own a vertical scroll state instead of rendering an unscrollable Column.",
            portraitBranch.contains(".verticalScroll(")
        )
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
