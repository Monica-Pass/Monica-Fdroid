package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordEditorIdentityRegressionGuardTest {

    @Test
    fun inlineEditorIsRecreatedWhenSwitchingBetweenEditAndAdd() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/ui/PasswordTabPane.kt").readText()

        assertTrue(
            "The inline password editor must receive a new Compose identity when its mode or entry changes.",
            source.contains("key(content)")
        )
    }

    @Test
    fun fullScreenEditorIsRecreatedWhenRouteEntryChanges() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/MainActivity.kt").readText()

        assertTrue(
            "The routed password editor must not retain the previous entry state when opening a new entry.",
            source.contains("key(passwordId)")
        )
    }

    @Test
    fun newModeNeverPassesRememberedIdsToTheSavePipeline() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt").readText()

        assertTrue(
            "New password mode must clear remembered update IDs before saving.",
            source.contains("originalIds = emptyList()") &&
                source.contains("originalIds = originalIds.takeIf { isEditing }.orEmpty()")
        )
        assertTrue(
            "New password mode must not reuse the previous replica group when resolving target rows.",
            source.contains("replicaGroupId = currentReplicaGroupId.takeIf { isEditing }")
        )
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
