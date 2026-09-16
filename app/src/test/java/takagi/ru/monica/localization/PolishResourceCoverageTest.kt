package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class PolishResourceCoverageTest {
    private data class Resource(
        val type: String,
        val formatted: String,
        val values: Map<String, String>,
    )

    @Test fun everyTranslatableResourceHasACompletePolishTranslation() {
        val source = resources("values")
        val polish = resources("values-pl")
        assertEquals("Polish must include every module, including legacy UI and new features", source.keys, polish.keys)
        source.forEach { (name, original) ->
            val translated = polish.getValue(name)
            assertEquals("$name type", original.type, translated.type)
            assertEquals("$name formatting mode", original.formatted, translated.formatted)
            if (original.type == "plurals") {
                assertEquals("$name Polish plural categories", setOf("one", "few", "many", "other"), translated.values.keys)
            } else {
                assertEquals("$name parts", original.values.keys, translated.values.keys)
            }
            translated.values.forEach { (part, text) ->
                val originalText = original.values[part] ?: original.values.getValue("other")
                assertTrue("$name/$part is empty", text.isNotBlank())
                assertEquals("$name/$part format arguments", tokens(FORMAT, originalText), tokens(FORMAT, text))
                assertEquals("$name/$part explicit line breaks", originalText.windowed(2).count { it == "\\n" },
                    text.windowed(2).count { it == "\\n" })
                assertEquals("$name/$part template tokens", tokens(TEMPLATE_TOKEN, originalText), tokens(TEMPLATE_TOKEN, text))
                assertEquals("$name/$part literal URLs", tokens(URL, originalText), tokens(URL, text))
                assertFalse("$name/$part has translation debris", Regex("ZXQ|ZZQX|ZZSPLIT|ZZXML|\\uFFFD").containsMatchIn(text))
                // Language names are intentionally kept in their native scripts.
                if (!name.startsWith("language_") && !name.startsWith("qs_lang_")) {
                    assertFalse("$name/$part still contains untranslated CJK text", CJK.containsMatchIn(text))
                }
            }
        }
    }

    @Test fun navigationAndCommonActionsStayCompact() {
        val polish = resources("values-pl")
        polish.filterKeys { it.startsWith("nav_") && it.endsWith("_short") }.forEach { (name, resource) ->
            val label = resource.values.getValue("text")
            assertTrue("$name must fit a compact navigation item: $label", label.length <= 8)
            assertFalse("$name must not force a second line", label.contains("\\n"))
        }
        listOf("save", "delete", "cancel", "edit", "add", "close", "copy", "ok").forEach { name ->
            assertTrue("$name is too long for a common action", polish.getValue(name).values.getValue("text").length <= 10)
        }
        assertEquals("Synchr.", polish.getValue("nav_v2_sync_short").values.getValue("text"))
        assertEquals("Powiel", polish.getValue("duplicate").values.getValue("text"))
        assertEquals("Archiwum", polish.getValue("vault_overview_archive").values.getValue("text"))
    }

    @Test fun polishPluralFormsAreNotCopiedFromEnglish() {
        assertEquals(
            mapOf("one" to "%1\$d wpis", "few" to "%1\$d wpisy", "many" to "%1\$d wpisów", "other" to "%1\$d wpisu"),
            resources("values-pl").getValue("vault_v2_hierarchy_item_count").values,
        )
    }

    private fun resources(directory: String): Map<String, Resource> {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (root.parentFile != null && !File(root, "settings.gradle.kts").exists() && !File(root, "settings.gradle").exists()) {
            root = root.parentFile.canonicalFile
        }
        val files = File(root, "app/src/main/res/$directory").listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue("Missing $directory", files.isNotEmpty())
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val result = linkedMapOf<String, Resource>()
        files.sortedBy(File::getName).forEach { file ->
            val children = parser.parse(file).documentElement.childNodes
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
                assertNull("Duplicate $name in ${file.name}", result.put(name, Resource(
                    element.tagName, element.getAttribute("formatted"), values,
                )))
            }
        }
        return result
    }

    private fun tokens(pattern: Regex, value: String) = pattern.findAll(value).map { it.value }.sorted().toList()

    private companion object {
        val FORMAT = Regex("""%(?:\d+\$)?[-#+ 0,(<]*(?:\d+|\*)?(?:\.\d+|\.\*)?(?:[tT][a-zA-Z]|[a-zA-Z%])""")
        val TEMPLATE_TOKEN = Regex("""\{[A-Za-z_][A-Za-z0-9_]*\}""")
        val URL = Regex("""(?:https?|otpauth|otpauth-migration|motp)://[A-Za-z0-9][^\s<>"\\]*""")
        val CJK = Regex("[\u3400-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]")
    }
}
