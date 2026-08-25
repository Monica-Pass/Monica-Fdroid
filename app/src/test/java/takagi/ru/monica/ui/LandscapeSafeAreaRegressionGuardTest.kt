package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeSafeAreaRegressionGuardTest {

    @Test
    fun wideMainSurfaceRespectsBothHorizontalSafeDrawingEdges() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()

        assertTrue(
            "Wide layouts must avoid display cutouts and side navigation bars",
            source.contains(
                ".windowInsetsPadding(" +
                    "WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)" +
                    ")"
            )
        )
    }

    @Test
    fun existingTopAndBottomInsetsRemainOwnedByWideNavigationRailAndContent() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()

        assertTrue(source.contains("val railTopInset = WindowInsets.statusBars"))
        assertTrue(source.contains("val railBottomInset = WindowInsets.navigationBars"))
        assertTrue(source.contains(".windowInsetsPadding(WindowInsets.statusBars)"))
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
