package takagi.ru.monica.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarContrastRegressionGuardTest {

    @Test
    fun edgeToEdgeSetupDisablesSystemNavigationBarContrastScrim() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/base/BaseMonicaActivity.kt"
        ).readText()

        val edgeToEdgeIndex = source.indexOf("enableEdgeToEdge()")
        val systemBarSetupIndex = source.indexOf("configureEdgeToEdgeSystemBars()")

        assertTrue("Edge-to-edge must be enabled", edgeToEdgeIndex >= 0)
        assertTrue(
            "System bar contrast must be configured after enableEdgeToEdge",
            systemBarSetupIndex > edgeToEdgeIndex
        )
        assertTrue(source.contains("window.isNavigationBarContrastEnforced = false"))
        assertTrue(source.contains("window.isStatusBarContrastEnforced = false"))
    }

    @Test
    fun composeThemeKeepsTransparentNavigationBarAcrossThemeChanges() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/theme/Theme.kt"
        ).readText()

        assertTrue(
            source.contains(
                "window.navigationBarColor = android.graphics.Color.TRANSPARENT"
            )
        )
        assertTrue(source.contains("isAppearanceLightNavigationBars = !darkTheme"))
        assertTrue(source.contains("window.isNavigationBarContrastEnforced = false"))
    }

    @Test
    fun lightAndDarkThemesDisableNavigationBarContrast() {
        val lightTheme = projectFile("app/src/main/res/values/themes.xml").readText()
        val darkTheme = projectFile("app/src/main/res/values-night/themes.xml").readText()
        val contrastSetting =
            "<item name=\"android:enforceNavigationBarContrast\">false</item>"

        assertTrue(lightTheme.contains(contrastSetting))
        assertTrue(darkTheme.contains(contrastSetting))
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
