package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import takagi.ru.monica.R

class ClassicalChineseResourceCoverageTest {
    private data class Resource(
        val type: String,
        val formatted: String,
        val parts: Map<String, String>,
    )

    @Test
    fun everyTranslatableResourceHasTheSameStructureAndArguments() {
        val source = resources("values")
        val classical = resources("values-b+lzh")
        assertEquals("All modules, including new features and extracted UI text", source.keys, classical.keys)
        source.forEach { (name, original) ->
            val translated = classical.getValue(name)
            assertEquals("$name resource type", original.type, translated.type)
            assertEquals("$name formatting mode", original.formatted, translated.formatted)
            assertEquals("$name plural quantities / array indices", original.parts.keys, translated.parts.keys)
            original.parts.forEach { (part, value) ->
                val text = translated.parts.getValue(part)
                if (value.isNotBlank()) assertTrue("$name/$part is empty", text.isNotBlank())
                assertEquals("$name/$part arguments", formats(value), formats(text))
                assertEquals("$name/$part explicit line breaks", value.split("\\n").size, text.split("\\n").size)
                assertEquals("$name/$part explicit spaces", value.split("\\u0020").size, text.split("\\u0020").size)
                assertFalse("$name/$part translation debris", Regex("ZXQ|ZZQX|ZZSPLIT|ZZXML|\uFFFD").containsMatchIn(text))
            }
        }
    }

    @Test
    fun technicalIdentifiersUrlsAndNumbersRemainIntact() {
        val classical = resources("values-b+lzh")
        val identifiers = listOf(
            "Monica", "KeePass", "KeePassXC", "Bitwarden", "Steam", "WebDAV", "OneDrive",
            "Android", "Windows", "GitHub", "Rust", "MDBX", "MDBX1", "MDBX2", "KDBX",
            "Argon2id", "Argon2", "AES-256", "SHA-256", "TOTP", "HOTP", "OTP", "API",
            "SSH", "RSA", "Ed25519", "Base32", "FIDO2", "WebAuthn", "OAuth", "OpenID",
            "JSON", "CSV", "ZIP", "HTTP", "HTTPS", "URI", "URL", "SSO", "PIN", "NFC",
            "Unicode", "PEM", "mTLS", "TLS", "PKCS#12",
        )
        resources("values").forEach { (name, original) ->
            original.parts.forEach { (part, value) ->
                val text = classical.getValue(name).parts.getValue(part)
                identifiers.forEach { identifier ->
                    val token = Regex("(?<![A-Za-z0-9])${Regex.escape(identifier)}(?![A-Za-z0-9])")
                    if (token.containsMatchIn(value)) {
                        assertTrue("$name/$part must retain $identifier", text.contains(identifier))
                    }
                }
                assertEquals("$name/$part numeric literals", numbers(value), numbers(text))
                Regex("""(?:https?://|otpauth://)[^\s<>"\\]+""").findAll(value).forEach { match ->
                    val url = match.value.trimEnd('.', ',', ';', ')')
                    assertTrue("$name/$part must retain $url", text.contains(url))
                }
                Regex("""\.(?:mdbx|kdbx|json|csv|zip|key|png|jpg|txt|pem|p12|pfx)\b""", RegexOption.IGNORE_CASE)
                    .findAll(value).forEach { match ->
                        assertTrue("$name/$part must retain ${match.value}", text.contains(match.value, ignoreCase = true))
                    }
            }
        }
    }

    @Test
    fun coreVocabularyAndFormattedNewFeaturesUseClassicalChinese() {
        val strings = xmlTestStrings("lzh")
        mapOf(
            R.string.settings to "设", R.string.search to "寻", R.string.save to "存",
            R.string.delete to "删", R.string.cancel to "罢", R.string.ok to "然",
            R.string.edit to "修", R.string.back to "返", R.string.close to "闭",
            R.string.copy to "摹", R.string.password to "密钥", R.string.master_password to "总钥",
            R.string.passkey to "通行之钥",
        ).forEach { (id, expected) -> assertEquals(expected, strings.get(id)) }
        assertEquals("事成，已删 3 条。", strings.get(R.string.legacy_ui_deleted_count, 3))
        assertEquals("果欲自库中删此 API 令牌乎？", strings.get(R.string.api_token_delete_confirmation))
        assertTrue(strings.get(R.string.wallet_stack_loop_desc).contains("首尾相续"))
        assertTrue(strings.get(R.string.language_classical_chinese).contains("文言文（华夏）"))
        assertTrue(strings.get(R.string.language_classical_chinese).contains("Classical Chinese (Huaxia)"))
    }

    private fun resources(directory: String): Map<String, Resource> {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/res").isDirectory }
        val files = File(root, "app/src/main/res/$directory").listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue("Missing $directory", files.isNotEmpty())
        val result = linkedMapOf<String, Resource>()
        files.sortedBy(File::getName).forEach { file ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array") || element.getAttribute("translatable") == "false") continue
                val parts = if (element.tagName == "string") mapOf("text" to element.textContent) else {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).associate { itemIndex ->
                        val item = items.item(itemIndex) as Element
                        (if (element.tagName == "plurals") item.getAttribute("quantity") else itemIndex.toString()) to item.textContent
                    }
                }
                val name = element.getAttribute("name")
                assertNull("Duplicate $name in ${file.name}", result.put(name, Resource(element.tagName, element.getAttribute("formatted"), parts)))
            }
        }
        return result
    }

    private fun formats(value: String) = FORMAT.findAll(value).map { it.value }.sorted().toList()
    private fun numbers(value: String) = Regex("""\d+(?:\.\d+)*""")
        .findAll(FORMAT.replace(value, "")).map { it.value }.sorted().toList()

    private companion object {
        val FORMAT = Regex("""%(?:\d+\$)?[-#+ 0,(<]*(?:\d+|\*)?(?:\.\d+|\.\*)?(?:[tT][a-zA-Z]|[a-zA-Z%])""")
    }
}
