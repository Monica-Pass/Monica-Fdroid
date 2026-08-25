package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WideMainContentInsetsGuardTest {

    @Test
    fun wideContentHostConsumesTopSystemInsetWithoutMovingNavigationRail() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()

        assertTrue(
            "The wide content host must stay below the status bar",
            source.contains(".windowInsetsPadding(WindowInsets.statusBars)")
        )
        assertTrue(
            "The navigation rail owns its own top inset",
            source.contains("val railTopInset = WindowInsets.statusBars")
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
