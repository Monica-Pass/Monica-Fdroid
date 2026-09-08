package takagi.ru.monica.versioning

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenVersionCodeGuardTest {

    @Test
    fun `fdroid version code matches synchronized release`() {
        val appBuild = projectFile("app/build.gradle").readText()

        assertTrue(appBuild.contains("versionCode 17"))
        assertTrue(appBuild.contains("versionName \"1.0.310\""))
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
