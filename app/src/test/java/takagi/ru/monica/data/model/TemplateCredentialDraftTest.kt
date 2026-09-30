package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry

class TemplateCredentialDraftTest {
    @Test fun apiKeyEndpointIsStoredTextNotARequiredWebLink() {
        listOf("djdjdmd", "api.example.org/v1", "localhost:11434/v1", "https://api.example.org/v1", "").forEach { endpoint ->
            val source = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_KEY)
                .edited("key", mapOf("key" to "合成测试密钥", "url" to endpoint, "notes" to "测试备注"))
            val draft = TemplateCredentialDraft(source.kind.name,
                PasswordContentBlocks.editableKeys(source.kind).associateWith(source::value))
            assertTrue("A recorded endpoint must not prevent saving: $endpoint", draft.valid())
            draft.validateKeyMaterial()
            val saved = PasswordContentBlocks.read(PasswordContentBlocks.put(emptyList(), source.edited(source.title, draft.values)))
                .single().block!!
            assertEquals(source.raw, saved.raw)
        }
    }

    private fun entry(type: String) = PasswordEntry(title = "title", website = "https://example.org", username = "hidden account",
        password = "  exact secret\n", loginType = type, notes = "notes", authenticatorKey = "otp-original")

    @Test fun apiKeyPreservesSecretWhitespaceAndUnrelatedFields() {
        val extra = CustomFieldDraft(id = -5, title = "future", value = "raw", isProtected = true)
        val fields = listOf(extra) + ApiKeyEntryFields.encode("https://example.org/api")
        val draft = requireNotNull(TemplateCredentialDraft.load(entry("API_KEY"), fields))
        assertEquals("  exact secret\n", draft.secret(""))
        assertEquals(extra, draft.change("url", "https://example.org/v2").fields(fields).first { it.title == "future" })
        assertTrue(draft.valid())
    }

    @Test fun wifiPatchesOnlyCoreKeysAndKeepsUnknownNetworkSettings() {
        val source = entry("WIFI").copy(wifiMetadata = """{"ssid":"old","security":"WPA2_ENTERPRISE","hiddenNetwork":false,"eap":{"method":"TLS","future":{"key":4}},"proxy":{"kind":"future","raw":[1,2]},"future":[true,"x"]}""")
        val draft = requireNotNull(TemplateCredentialDraft.load(source, emptyList())).change("ssid", "new")
        val old = Json.parseToJsonElement(source.wifiMetadata).jsonObject
        val updated = Json.parseToJsonElement(draft.wifi()).jsonObject
        listOf("eap", "proxy", "future", "security").forEach { assertEquals(old[it], updated[it]) }
        assertEquals("new", updated["ssid"]!!.jsonPrimitive.content)
        assertEquals(source.password, draft.secret(""))
    }

    @Test fun wifiUnknownSecurityIsNotReset() {
        val draft = requireNotNull(TemplateCredentialDraft.load(entry("WIFI").copy(wifiMetadata = """{"ssid":"future","security":"WPA_FUTURE"}"""), emptyList()))
        assertEquals("WPA_FUTURE", Json.parseToJsonElement(draft.wifi()).jsonObject["security"]!!.jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class) fun corruptWifiCannotBecomeEmptySettings() {
        TemplateCredentialDraft.load(entry("WIFI").copy(wifiMetadata = "broken"), emptyList())
    }

    @Test fun sshPreservesUnknownFieldsAndIndependentPassword() {
        val old = SshKeyData(algorithm = "ED25519", privateKeyOpenSsh = "  private\n", publicKeyOpenSsh = "public",
            additionalFields = mapOf("future" to buildJsonObject { put("nested", true) }))
        val source = entry("SSH_KEY").copy(sshKeyData = SshKeyDataCodec.encode(old))
        val draft = requireNotNull(TemplateCredentialDraft.load(source, emptyList())).change("comment", "updated")
        val saved = requireNotNull(SshKeyDataCodec.decode(draft.ssh()))
        assertEquals(old.additionalFields, saved.additionalFields)
        assertEquals(old.privateKeyOpenSsh, saved.privateKeyOpenSsh)
        assertEquals(source.password, draft.secret(source.password))
    }

    @Test(expected = IllegalArgumentException::class) fun corruptSshCannotBeOverwrittenByBlankDraft() {
        TemplateCredentialDraft.load(entry("SSH_KEY").copy(sshKeyData = "broken"), emptyList())
    }

    @Test fun gpgUsesChunkCodecWithoutChangingSecretBytes() {
        val key = takagi.ru.monica.utils.GpgKeyGenerator.Key("public\n".repeat(1000), " private\n", "fingerprint", "user")
        val custom = listOf(CustomFieldDraft(title = "future", value = "unchanged")) + GpgEntryFields.encode(key)
        val draft = requireNotNull(TemplateCredentialDraft.load(entry("GPG_KEY").copy(password = key.privateKey), custom))
        assertEquals(key.privateKey, draft.secret(""))
        assertEquals(key.publicKey, GpgEntryFields.publicKey(draft.fields(custom).associate { it.title to it.value }))
        assertEquals("unchanged", draft.fields(custom).first { it.title == "future" }.value)
    }

    @Test fun oldApiAndGpgMarkersStillSelectCorrectEditor() {
        assertEquals("API_KEY", TemplateCredentialDraft.load(entry("PASSWORD"), ApiKeyEntryFields.encode(""))!!.type)
        val key = takagi.ru.monica.utils.GpgKeyGenerator.Key("public", "", "fp", "id")
        assertEquals("GPG_KEY", TemplateCredentialDraft.load(entry("PASSWORD"), GpgEntryFields.encode(key))!!.type)
        assertNull(TemplateCredentialDraft.load(entry("PASSWORD"), emptyList()))
        assertNull(TemplateCredentialDraft.load(entry("API_TOKEN"), emptyList()))
    }
}
