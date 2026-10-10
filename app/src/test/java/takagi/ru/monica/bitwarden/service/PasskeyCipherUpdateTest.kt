package takagi.ru.monica.bitwarden.service

import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.data.PasskeyEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PasskeyCipherUpdateTest {
    private val key = BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 1 }, ByteArray(32) { 2 })
    private val material = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private.encoded)
    private val item = PasskeyEntry(credentialId = "Y3JlZGVudGlhbA", rpId = "example.test", rpName = "New RP",
        userId = "dXNlcg", userName = "New user", userDisplayName = "New display", publicKey = "public",
        privateKeyAlias = material, notes = "New notes", passkeyMode = PasskeyEntry.MODE_BW_COMPAT)
    private fun enc(text: String, k: BitwardenCrypto.SymmetricCryptoKey = key) = JsonPrimitive(BitwardenCrypto.encryptString(text, k))
    private fun document(k: BitwardenCrypto.SymmetricCryptoKey = key): JsonObject = buildJsonObject {
        put("id", "cipher"); put("type", 1); put("revisionDate", "2026-10-10T00:00:00.000Z")
        put("name", enc("Shared login", k)); put("notes", enc("Original notes", k)); put("folderId", "folder")
        put("favorite", true); put("reprompt", 1); put("collectionIds", buildJsonArray { add("collection") })
        put("unknownFutureField", buildJsonObject { put("unknown", "preserve") })
        put("fields", buildJsonArray { add(buildJsonObject { put("name", enc("Custom", k)); put("value", enc("Secret", k)); put("type", 1) }) })
        put("login", buildJsonObject {
            put("username", enc("Login", k)); put("password", enc("Secret password", k)); put("totp", enc("TOTP", k))
            put("uris", buildJsonArray { add(buildJsonObject { put("uri", enc("https://example.test", k)); put("match", 3) }) })
            put("futureLoginField", "preserve too")
            put("fido2Credentials", buildJsonArray {
                add(buildJsonObject {
                    put("credentialId", enc("b64.Y3JlZGVudGlhbA", k)); put("rpId", enc(item.rpId, k))
                    put("rpName", enc("Old RP", k)); put("userHandle", enc("dXNlcg==", k))
                    put("userName", enc("Old user", k)); put("userDisplayName", enc("Old display", k))
                    put("keyValue", enc(material, k)); put("counter", enc("41", k)); put("keyAlgorithm", enc("ECDSA", k))
                    put("discoverable", enc("true", k)); put("futureCredentialField", "keep")
                })
                add(buildJsonObject { put("credentialId", enc("b64.c2libGluZw", k)); put("keyValue", enc("Unrelated key", k)) })
            })
        })
    }
    private fun target(doc: JsonObject) = doc["login"]!!.jsonObject["fido2Credentials"]!!.jsonArray[0].jsonObject
    private fun replacing(doc: JsonObject, field: String, value: JsonElement) = JsonObject(doc + (field to value))
    private fun rejects(doc: JsonObject = document(), row: PasskeyEntry = item) {
        try { PasskeyCipherUpdate.build(doc, row, "cipher", key); fail("Unsafe update was accepted") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun patchesOnlyRequestedCredentialAndNotesInFullEncryptedCipher() {
        val before = document()
        val after = PasskeyCipherUpdate.build(before, item, "cipher", key)
        for ((name, value) in before) if (name !in setOf("login", "notes")) assertEquals(name, value, after[name])
        val login = before["login"]!!.jsonObject; val updated = after["login"]!!.jsonObject
        for ((name, value) in login) if (name != "fido2Credentials") assertEquals(name, value, updated[name])
        assertEquals(login["fido2Credentials"]!!.jsonArray[1], updated["fido2Credentials"]!!.jsonArray[1])
        for ((name, value) in target(before)) if (name !in setOf("rpName", "userName", "userDisplayName")) assertEquals(name, value, target(after)[name])
        assertEquals("New user", BitwardenCrypto.decryptToString(target(after)["userName"]!!.jsonPrimitive.content, key))
        assertEquals("41", BitwardenCrypto.decryptToString(target(after)["counter"]!!.jsonPrimitive.content, key))
        assertEquals(before["revisionDate"], after["lastKnownRevisionDate"])
        assertTrue(PasskeyCipherUpdate.confirms(after, after))
    }

    @Test fun decryptsAndReencryptsWithPerItemKeyWithoutChangingWrappedKey() {
        val itemKey = BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 3 }, ByteArray(32) { 4 })
        val wrapped = JsonPrimitive(BitwardenCrypto.encrypt(itemKey.encKey + itemKey.macKey!!, key))
        val before = replacing(document(itemKey), "key", wrapped)
        val after = PasskeyCipherUpdate.build(before, item.copy(signCount = 42), "cipher", key)
        assertEquals(wrapped, after["key"])
        assertEquals("42", BitwardenCrypto.decryptToString(target(after)["counter"]!!.jsonPrimitive.content, itemKey))
        assertEquals("New user", BitwardenCrypto.decryptToString(target(after)["userName"]!!.jsonPrimitive.content, itemKey))
        assertEquals(1, key.encKey[0].toInt())
    }

    @Test fun rejectsChangedIdentityOrMissingTargetWithoutProducingReplacement() {
        rejects(row = item.copy(credentialId = "b3RoZXI"))
        rejects(row = item.copy(rpId = "different.test"))
        rejects(row = item.copy(userId = "b3RoZXI"))
        rejects(row = item.copy(privateKeyAlias = "missing-key"))
        rejects(row = item.copy(backupEligible = false))
        rejects(row = item.copy(publicKeyAlgorithm = -257))
        rejects(row = item.copy(signCount = -1))
    }

    @Test fun rejectsDeletedReadonlyOrganizationAndMissingRevision() {
        rejects(replacing(document(), "deletedDate", JsonPrimitive("2026-10-10")))
        rejects(replacing(document(), "edit", JsonPrimitive(false)))
        rejects(replacing(document(), "organizationId", JsonPrimitive("org")))
        rejects(JsonObject(document() - "revisionDate"))
        rejects(replacing(document(), "key", enc("invalid wrapped key")))
    }

    @Test fun refusesAmbiguousCredentialIdsAndCasingAliases() {
        val before = document(); val login = before["login"]!!.jsonObject
        val duplicated = replacing(login, "fido2Credentials", JsonArray(listOf(target(before), target(before))))
        rejects(replacing(before, "login", duplicated))
        rejects(replacing(before, "Login", login))
    }

    @Test fun verificationRejectsDroppedSiblingOrChangedPasswordButAllowsServerRevision() {
        val request = PasskeyCipherUpdate.build(document(), item, "cipher", key)
        assertTrue(PasskeyCipherUpdate.confirms(request, replacing(request, "revisionDate", JsonPrimitive("later"))))
        val login = request["login"]!!.jsonObject
        assertFalse(PasskeyCipherUpdate.confirms(request, replacing(request, "login", replacing(login, "password", enc("changed")))))
        assertFalse(PasskeyCipherUpdate.confirms(request, replacing(request, "login", replacing(login, "fido2Credentials", JsonArray(listOf(target(request)))))))
    }
}
