package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleResourceCoverageTest {
    private val localeTags = listOf("ko", "de", "es")

    @Test
    fun newLocalesMatchTheEnglishResourceContract() {
        val base = parseStrings(projectFile("app/src/main/res/values/strings.xml"))

        localeTags.forEach { localeTag ->
            val file = projectFile("app/src/main/res/values-$localeTag/strings.xml")
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val localized = parseStrings(file)

            assertEquals("$localeTag string keys", base.keys, localized.keys)
            base.forEach { (name, sourceValue) ->
                val localizedValue = localized.getValue(name)
                if (sourceValue.isNotBlank()) {
                    assertTrue("$localeTag/$name must not be empty", localizedValue.isNotBlank())
                }
                assertEquals(
                    "$localeTag/$name placeholders",
                    placeholders(sourceValue),
                    placeholders(localizedValue)
                )
                assertFalse(
                    "$localeTag/$name contains a translation guard token",
                    GUARD_TOKEN.containsMatchIn(localizedValue)
                )
            }
        }
    }

    @Test
    fun languageSelectorsAndLocaleResolutionRegisterAllNewLocales() {
        val settings = projectFile(
            "app/src/main/java/takagi/ru/monica/data/AppSettings.kt"
        ).readText()
        val localeHelper = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/LocaleHelper.kt"
        ).readText()
        val settingsScreen = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/SettingsScreen.kt"
        ).readText()
        val quickSetup = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/QuickSetupScreen.kt"
        ).readText()

        listOf("KOREAN", "GERMAN", "SPANISH").forEach { language ->
            assertTrue(settings.contains(language))
            assertTrue(settingsScreen.contains("Language.$language"))
            assertTrue(quickSetup.contains("Language.$language"))
        }
        assertTrue(localeHelper.contains("Language.KOREAN -> Locale.KOREA"))
        assertTrue(localeHelper.contains("Language.GERMAN -> Locale.GERMANY"))
        assertTrue(localeHelper.contains("Language.SPANISH -> Locale(\"es\", \"ES\")"))
        assertTrue(localeHelper.contains("\"ko\" -> Language.KOREAN"))
        assertTrue(localeHelper.contains("\"de\" -> Language.GERMAN"))
        assertTrue(localeHelper.contains("\"es\" -> Language.SPANISH"))
    }

    private fun parseStrings(file: File): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        STRING.findAll(file.readText()).forEach { match ->
            val attributes = match.groupValues[1]
            val name = NAME.find(attributes)?.groupValues?.get(1)
                ?: error("Missing string name in ${file.path}")
            check(result.put(name, match.groupValues[2]) == null) {
                "Duplicate string $name in ${file.path}"
            }
        }
        return result
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.sorted().toList()

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

    private companion object {
        val STRING = Regex("""<string\b([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val NAME = Regex("""\bname\s*=\s*"([^"]+)"""")
        val PLACEHOLDER = Regex("""%(?:\d+\$)?[-+# 0,(]*(?:\d+|\*)?(?:\.\d+|\.\*)?[a-zA-Z]""")
        val GUARD_TOKEN = Regex("""ZZ(?:QX|SPLIT|XML)""")
    }
}
