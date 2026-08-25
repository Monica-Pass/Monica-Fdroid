package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpEditorIdentityRegressionGuardTest {

    @Test
    fun inlineTotpEditorIsRecreatedForAddAndEditItems() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/ui/AuthenticatorTabPane.kt").readText()

        assertTrue(source.contains("key(\"new\")"))
        assertTrue(source.contains("key(selectedTotpItem.id)"))
    }

    @Test
    fun routedTotpEditorIsRecreatedWhenTheRouteIdChanges() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/MainActivity.kt").readText()
        assertTrue(source.contains("key(totpId)"))
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
