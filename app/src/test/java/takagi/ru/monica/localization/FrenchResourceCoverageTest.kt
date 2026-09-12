package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class FrenchResourceCoverageTest {
    private data class Resource(val type: String, val values: Map<String, String>)

    @Test fun everyTranslatableResourceIsPresentAcrossTheFrenchModules() {
        val base = resources("values")
        val french = resources("values-fr")
        assertEquals("French resource names, including modular files", base.keys, french.keys)
        base.forEach { (name, source) ->
            val translated = french.getValue(name)
            assertEquals("$name type", source.type, translated.type)
            assertEquals("$name quantities or array indices", source.values.keys, translated.values.keys)
            source.values.forEach { (part, sourceText) ->
                val text = translated.values.getValue(part)
                if (sourceText.isNotBlank()) assertTrue("$name/$part is empty", text.isNotBlank())
                assertEquals("$name/$part format arguments", placeholders(sourceText), placeholders(text))
                assertEquals("$name/$part explicit line breaks", sourceText.windowed(2).count { it == "\\n" },
                    text.windowed(2).count { it == "\\n" })
                assertFalse("$name/$part has translation debris", Regex("ZXQ|ZZQX|ZZSPLIT|ZZXML|\\uFFFD").containsMatchIn(text))
                for (brand in listOf("Monica", "KeePass", "Bitwarden", "Steam", "WebDAV", "MDBX")) {
                    if (sourceText.contains(brand, ignoreCase = true)) {
                        assertTrue("$name/$part must preserve $brand", text.contains(brand, ignoreCase = true))
                    }
                }
            }
        }
    }

    private fun resources(directory: String): Map<String, Resource> {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (root.parentFile != null && !File(root, "settings.gradle.kts").exists() && !File(root, "settings.gradle").exists()) {
            root = root.parentFile.canonicalFile
        }
        val files = File(root, "app/src/main/res/$directory").listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue("Missing $directory", files.isNotEmpty())
        val result = linkedMapOf<String, Resource>()
        files.sortedBy(File::getName).forEach { file ->
            val children = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array") || element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                val values = if (element.tagName == "string") mapOf("text" to element.textContent) else {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).associate { itemIndex ->
                        val item = items.item(itemIndex) as Element
                        (if (element.tagName == "plurals") item.getAttribute("quantity") else itemIndex.toString()) to item.textContent
                    }
                }
                assertNull("Duplicate $name in ${file.name}", result.put(name, Resource(element.tagName, values)))
            }
        }
        return result
    }

    private fun placeholders(value: String) = Regex("""%(?:\d+\$)?[-+# 0,(]*(?:\d+|\*)?(?:\.\d+|\.\*)?[a-zA-Z]""")
        .findAll(value).map { it.value }.sorted().toList()
}
