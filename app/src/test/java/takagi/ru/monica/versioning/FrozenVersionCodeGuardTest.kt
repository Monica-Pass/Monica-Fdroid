package takagi.ru.monica.versioning

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenVersionCodeGuardTest {

    @Test
    fun `version code remains frozen for same-signature rollback installs`() {
        val appBuild = projectFile("app/build.gradle").readText()

        assertTrue(appBuild.contains("def appVersionCode = 12"))
        assertTrue(appBuild.contains("Do not increment this value for routine releases."))
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
