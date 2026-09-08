package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class PasswordListScrollPerformanceGuardTest {
    @Test
    fun scrollPersistenceIsNotCollectedAsScreenStateDuringFling() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContentSupport.kt"
        ).readText()

        assertFalse(source.contains("viewModel.passwordListScrollIndex.collectAsState()"))
        assertFalse(source.contains("viewModel.passwordListScrollOffset.collectAsState()"))
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
