package takagi.ru.monica

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionMetadataRegressionGuardTest {

    @Test
    fun `system version uses complete build while version code remains frozen`() {
        val buildScript = projectFile("app/build.gradle").readText()

        assertTrue(buildScript.contains("def appVersionCode = 12"))
        assertTrue(buildScript.contains("\"1.0.304-\${previewVersionSuffix}\" : \"1.0.304\""))
        assertTrue(buildScript.contains("versionName fullVersionName"))
        assertTrue(buildScript.contains("'BASE_VERSION_NAME', \"\\\"\${baseVersionName}\\\"\""))
        assertTrue(buildScript.contains("'FULL_VERSION_NAME', \"\\\"\${fullVersionName}\\\"\""))
        assertTrue(buildScript.contains("project.findProperty('apkVersionName') ?: baseVersionName"))
        assertFalse(buildScript.contains("versionName baseVersionName"))
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
