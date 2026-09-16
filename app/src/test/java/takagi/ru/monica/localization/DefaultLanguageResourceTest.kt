package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class DefaultLanguageResourceTest {
    @Test fun englishFallbackDoesNotContainChineseUiText() {
        val directory = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, "app/src/main/res/values") }.first { it.isDirectory }
        val files = directory.listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue(files.isNotEmpty())
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val cjk = Regex("[\\u3400-\\u9fff]")
        files.forEach { file ->
            val children = parser.parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array")) continue
                val name = element.getAttribute("name")
                // A language picker must retain native names, including 中文 and 日本語.
                if (name.startsWith("language_") || name.startsWith("qs_lang_")) continue
                assertFalse("${file.name}: $name contains Chinese in the English fallback",
                    cjk.containsMatchIn(element.textContent))
            }
        }
    }
}
