package takagi.ru.monica.keepass

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeePassPasswordEntryAttachmentRegressionGuardTest {

    @Test
    fun passwordUpdatesReuseExistingEntrySoAttachmentsSurviveSave() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()
        val applierSource = projectFile(
            "app/src/main/java/takagi/ru/monica/keepass/KeePassChangeSetApplier.kt"
        ).readText()

        val updateBody = source.substringAfter("private fun updateEntry(")
            .substringBefore("private fun updateSecureItemInternal(")

        assertTrue(
            "Updating a KeePass password entry must resolve and retain the matched native Entry context.",
            updateBody.contains("matchedContext = entryContexts.firstOrNull")
        )
        assertTrue(
            "Foreground updates must use the same native change-set path as remote replay.",
            updateBody.contains("KeePassChangeSetApplier().applyAll")
        )
        assertTrue(
            "The change-set applier must patch the current native Entry through KeePassNativeMutation, " +
                "so binaries and unknown metadata survive.",
            applierSource.contains("nativeMutation.editEntry(") &&
                applierSource.contains("KeePassEntryFieldPatch.fromEntryFields(") &&
                applierSource.contains(").applyTo(current)")
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }
}
