package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVersionDisplayRegressionGuardTest {

    @Test
    fun settingsVersionUsesInstalledBuildInsteadOfHardcodedResource() {
        val projectRoot = projectRoot()
        val settingsSource = File(
            projectRoot,
            "app/src/main/java/takagi/ru/monica/ui/screens/SettingsScreen.kt"
        ).readText()

        assertTrue(settingsSource.contains("val installedVersion = BuildConfig.VERSION_NAME"))
        assertTrue(settingsSource.contains("subtitle = installedVersion"))
        assertTrue(settingsSource.contains("val currentVersion = BuildConfig.BASE_VERSION_NAME"))

        val resourceFiles = File(projectRoot, "app/src/main/res")
            .walkTopDown()
            .filter { it.isFile && it.name == "strings.xml" }
            .toList()

        assertTrue(resourceFiles.isNotEmpty())
        resourceFiles.forEach { resourceFile ->
            assertFalse(
                "Version must not be hardcoded in ${resourceFile.path}",
                resourceFile.readText().contains("settings_version_number")
            )
        }
    }

    private fun projectRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return directory
    }
}
