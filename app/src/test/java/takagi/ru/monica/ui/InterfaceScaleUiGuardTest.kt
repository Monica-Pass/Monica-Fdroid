package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceScaleUiGuardTest {
    @Test
    fun appearanceSettingsExposeStableDpiSliderAndReset() {
        val contentFile = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/InterfaceScaleSettingsContent.kt"
        )
        val settingsScreen = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/SettingsScreen.kt"
        ).readText()

        assertTrue(contentFile.exists())
        val content = contentFile.readText()
        assertTrue(content.contains("Slider("))
        assertTrue(content.contains("onValueChangeFinished"))
        assertTrue(content.contains("InterfaceScale.calculateEffectiveDpi("))
        assertTrue(content.contains("LocalDensity provides systemDensity"))
        assertTrue(content.contains("stateDescription"))
        assertTrue(content.contains("InterfaceScale.DEFAULT_PERCENT"))
        assertTrue(settingsScreen.contains("showInterfaceScaleSheet"))
        assertTrue(settingsScreen.contains("InterfaceScaleSettingsItem("))
        assertTrue(settingsScreen.contains("InterfaceScaleSelectionSheet("))
        assertTrue(settingsScreen.contains("viewModel.updateInterfaceScalePercent("))
        assertTrue(settingsScreen.contains("R.string.interface_scale_title"))
    }

    @Test
    fun scaleStringsDescribeDpiAndSystemIsolation() {
        val english = projectFile("app/src/main/res/values/strings.xml").readText()
        val chinese = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        listOf(english, chinese).forEach { strings ->
            assertTrue(strings.contains("name=\"interface_scale_title\""))
            assertTrue(strings.contains("name=\"interface_scale_current\""))
            assertTrue(strings.contains("name=\"interface_scale_description\""))
            assertTrue(strings.contains("name=\"interface_scale_reset\""))
            assertTrue(strings.contains("DPI"))
        }
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
