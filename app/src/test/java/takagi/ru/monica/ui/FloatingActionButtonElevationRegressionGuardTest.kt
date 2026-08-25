package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingActionButtonElevationRegressionGuardTest {

    @Test
    fun sharedMainScreenFabUsesMaterialElevation() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/SwipeableAddFab.kt"
        ).readText()

        assertTrue(source.contains("FloatingActionButton("))
        assertTrue(source.contains("elevation = FloatingActionButtonDefaults.elevation("))
        assertTrue(source.contains("defaultElevation = 6.dp"))
        assertTrue(source.contains("pressedElevation = 8.dp"))
        assertTrue(source.contains("focusedElevation = 6.dp"))
        assertTrue(source.contains("hoveredElevation = 8.dp"))
    }

    @Test
    fun backToTopFabDoesNotClipItsSurfaceShadow() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/MainScreenFab.kt"
        ).readText()
        val backToTopSection = source
            .substringAfter("visible = shouldShowBackToTopFab")
            .substringBefore("MainScreenAddFab(")

        assertTrue(backToTopSection.contains("shadowElevation = 4.dp"))
        assertFalse(backToTopSection.contains(".clip(RoundedCornerShape(12.dp))"))
    }

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
