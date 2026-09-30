package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PasswordQrTemplateTest {
    private val values = PasswordQrTemplate.Values(mapOf("ACCOUNT" to "测试;net", "PASSWORD" to "p:a,\\\"%ACCOUNT%", "TITLE" to "Home"))
    @Test fun wifiPresetEscapesInsertedValuesWithoutRecursiveExpansion() {
        assertEquals("WIFI:T:WPA;S:测试\\;net;P:p\\:a\\,\\\\\\\"%ACCOUNT%;H:false;;", PasswordQrTemplate.render(PasswordQrTemplate.WIFI, values))
    }
    @Test fun generalTemplatesKeepWhitespaceUnicodeAndRepeatedFields() {
        assertEquals(" 测试;net\n测试;net\n% Home", PasswordQrTemplate.render(" %ACCOUNT%\n%ACCOUNT%\n%% %TITLE%", values))
    }
    @Test fun literalContentIsNeverExpanded() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("", mapOf("content" to "%PASSWORD%"))
        assertEquals("%PASSWORD%", PasswordQrTemplate.resolve(block, values))
    }
    @Test fun missingUnreadableAndAmbiguousFieldsFailWithoutLeakingValues() {
        for (input in listOf("%UNKNOWN%", "%EMAIL%", "%ACCOUNT:typo%")) {
            assertThrows(PasswordQrTemplate.Invalid::class.java) { PasswordQrTemplate.render(input, values) }
        }
        val name = "自定义%账户:名称"
        val token = PasswordQrTemplate.customToken(name)
        assertThrows(PasswordQrTemplate.Invalid::class.java) {
            PasswordQrTemplate.render(token, values.copy(custom = listOf(name to "one", name to "two")))
        }
        assertThrows(PasswordQrTemplate.Invalid::class.java) { PasswordQrTemplate.render("%PASSWORD%", values.copy(fields = mapOf("PASSWORD" to null))) }
    }
    @Test fun customFieldReferencesRemainStableAcrossLocalIds() {
        val name = "自定义%账户:名称"
        assertEquals("秘密\n🔐", PasswordQrTemplate.render(PasswordQrTemplate.customToken(name), values.copy(custom = listOf(name to "秘密\n🔐"))))
    }
    @Test fun savedTemplateUsesLatestValuesAndNeverStoresExpandedSecrets() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("Wi-Fi", mapOf(
            "mode" to "template", "templateVersion" to "1", "content" to PasswordQrTemplate.WIFI))
        val fields = PasswordContentBlocks.put(emptyList(), block)
        val restored = PasswordContentBlocks.read(fields).single().block!!
        assertEquals(PasswordQrTemplate.WIFI, restored.value("content"))
        assertFalse(restored.raw.toString().contains("new-secret"))
        assertTrue(PasswordQrTemplate.resolve(restored, values.copy(fields = values.fields + ("PASSWORD" to "new-secret"))).contains("P:new-secret;"))
    }
    @Test fun unknownTemplateVersionCannotOverwriteStoredContent() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE).edited("", mapOf("mode" to "template", "templateVersion" to "9"))
        assertThrows(IllegalArgumentException::class.java) { PasswordContentBlocks.put(emptyList(), block) }
    }
    @Test fun largeExpansionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PasswordQrTemplate.render("%PASSWORD%", values.copy(fields = mapOf("PASSWORD" to "密".repeat(100000)))) }
    }
}
