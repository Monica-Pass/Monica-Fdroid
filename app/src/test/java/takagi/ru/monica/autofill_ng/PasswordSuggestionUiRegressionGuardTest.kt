package takagi.ru.monica.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordSuggestionUiRegressionGuardTest {

    @Test
    fun passwordSuggestionUsesUnifiedAutofillCard() {
        val builder = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/builder/AutofillDatasetBuilder.kt"
        ).readText()
        val factoryBody = builder.substringAfter("fun createPasswordSuggestion(context: Context)")
            .substringBefore("\n        }")

        assertTrue(factoryBody.contains("R.layout.autofill_dataset_card"))
        assertTrue(factoryBody.contains("R.id.text_title"))
        assertTrue(factoryBody.contains("R.id.text_username"))
        assertFalse(factoryBody.contains("R.layout.autofill_suggestion_item"))
    }

    @Test
    fun passwordSuggestionDialogRemainsAdaptive() {
        val activity = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/PasswordSuggestionActivity.kt"
        ).readText()

        assertTrue(activity.contains("DialogProperties(usePlatformDefaultWidth = false)"))
        assertTrue(activity.contains(".widthIn(max = 420.dp)"))
        assertTrue(activity.contains(".heightIn(max = 640.dp)"))
        assertTrue(activity.contains(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun primaryActionUsesShortLabelInEnglishAndChinese() {
        val english = projectFile("app/src/main/res/values/strings.xml").readText()
        val chinese = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(english.contains("<string name=\"password_suggestion_accept\">Use</string>"))
        assertTrue(chinese.contains("<string name=\"password_suggestion_accept\">使用</string>"))
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
