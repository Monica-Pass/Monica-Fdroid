package takagi.ru.monica

import takagi.ru.monica.testing.readSourceText

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionMetadataRegressionGuardTest {

    @Test
    fun `fdroid version metadata remains static and reproducible`() {
        val buildScript = projectFile("app/build.gradle").readSourceText()

        assertTrue(buildScript.contains("versionCode 21"))
        assertTrue(buildScript.contains("versionName \"1.0.314\""))
        assertTrue(buildScript.contains("'BASE_VERSION_NAME', '\"1.0.314\"'"))
        assertTrue(buildScript.contains("'FULL_VERSION_NAME', '\"1.0.314\"'"))
        assertTrue(buildScript.contains("'BUILD_TIME', '\"\"'"))
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
