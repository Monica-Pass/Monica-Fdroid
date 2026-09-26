package takagi.ru.monica.ui.theme

import takagi.ru.monica.testing.styleItem

import takagi.ru.monica.testing.readSourceText

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashThemeResourceTest {
    @Test
    fun systemSplashFallbackUsesMonicaM3LightAndDarkColors() {
        val lightColors = projectFile("app/src/main/res/values/colors.xml").readSourceText()
        val darkColors = projectFile("app/src/main/res/values-night/colors.xml").readSourceText()
        val lightApi31 = projectFile("app/src/main/res/values-v31/colors.xml").readSourceText()
        val darkApi31 = projectFile("app/src/main/res/values-night-v31/colors.xml").readSourceText()
        val lightTheme = projectFile("app/src/main/res/values/themes.xml").readSourceText()
        val darkTheme = projectFile("app/src/main/res/values-night/themes.xml").readSourceText()

        assertTrue(lightColors.contains("<color name=\"monica_startup_background\">#F7F2FA</color>"))
        assertTrue(darkColors.contains("<color name=\"monica_startup_background\">#1C1B1F</color>"))
        assertTrue(!lightApi31.contains("system_neutral"))
        assertTrue(!darkApi31.contains("system_neutral"))
        assertTrue(lightApi31.contains("android:windowSplashScreenBackground"))
        assertTrue(darkApi31.contains("android:windowSplashScreenBackground"))
        assertTrue(
            lightApi31.contains(
                "<item name=\"android:windowSplashScreenIconBackgroundColor\">" +
                    "@android:color/transparent</item>"
            )
        )
        assertTrue(
            darkApi31.contains(
                "<item name=\"android:windowSplashScreenIconBackgroundColor\">" +
                    "@android:color/transparent</item>"
            )
        )
        assertTrue(lightTheme.styleItem("Theme.Monica", "android:windowLightStatusBar") == "true")
        assertTrue(lightTheme.styleItem("Theme.Monica", "android:windowLightNavigationBar") == "true")
        assertTrue(darkTheme.styleItem("Theme.Monica", "android:windowLightStatusBar") == "false")
        assertTrue(darkTheme.styleItem("Theme.Monica", "android:windowLightNavigationBar") == "false")
    }

    @Test
    fun startupUsesOnlyTheAndroidSystemSplashLayer() {
        val mainActivity = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readSourceText()
        val baseActivity = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/base/BaseMonicaActivity.kt"
        ).readSourceText()

        assertTrue(!mainActivity.contains("MonicaStartupSplash"))
        assertTrue(!mainActivity.contains("doOnPreDraw"))
        assertTrue(!mainActivity.contains("private fun initializeMainContent()"))
        assertTrue(!baseActivity.contains("cachedSettings = startupSettings"))
        assertTrue(mainActivity.split("installSplashScreen()").size - 1 == 1)
        assertTrue(mainActivity.contains("setContent {"))
        assertTrue(mainActivity.contains("MonicaApp("))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
