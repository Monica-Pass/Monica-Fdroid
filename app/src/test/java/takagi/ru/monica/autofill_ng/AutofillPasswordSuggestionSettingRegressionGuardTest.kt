package takagi.ru.monica.autofill_ng

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillPasswordSuggestionSettingRegressionGuardTest {

    @Test
    fun settingsScreenControlsExistingPasswordSuggestionPreference() {
        val settings = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AutofillSettingsV2Screen.kt"
        ).readText()

        assertTrue(
            settings.contains(
                "preferences.isPasswordSuggestionEnabled.collectAsState(initial = true)"
            )
        )
        assertTrue(settings.contains("checked = passwordSuggestionEnabled"))
        assertTrue(settings.contains("preferences.setPasswordSuggestionEnabled(enabled)"))
    }

    @Test
    fun serviceAndResponseBuilderHonorDisabledPreference() {
        val service = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/MonicaAutofillServiceNg.kt"
        ).readText()
        val builder = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/builder/FillResponseBuilderNg.kt"
        ).readText()

        assertTrue(
            service.contains(
                "passwordSuggestionEnabled = autofillPreferences.isPasswordSuggestionEnabled.first()"
            )
        )
        assertTrue(builder.contains("if (passwordSuggestionEnabled)"))
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
