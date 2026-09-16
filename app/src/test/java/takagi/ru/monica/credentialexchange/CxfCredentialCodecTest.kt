package takagi.ru.monica.credentialexchange

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class CxfCredentialCodecTest {
    private val types = setOf("basic-auth", "passkey")

    @Test fun passwordFieldsAndUnicodeSurviveTheOfficialEditableFieldShape() {
        val source = CxfCredentialCodec.Item("1", "Zażółć · 華夏", urls = listOf("https://example.com/path?q=1&n=2"),
            logins = listOf(CxfCredentialCodec.Login("  alice\n@example.com  ", " \"'<&>\\\n🔑  ")),
            createdAt = 1_700_000_000_000, modifiedAt = 1_700_000_001_000)
        val encoded = CxfCredentialCodec.encode(listOf(source), "test", types, nowMillis = 1_750_000_000_000)
        val decoded = CxfCredentialCodec.decode(encoded)
        assertEquals(1, decoded.passwordCount)
        assertEquals(source.logins, decoded.items.single().logins)
        assertEquals(source.urls, decoded.items.single().urls)
        assertEquals(source.title, decoded.items.single().title)
        assertEquals(source.createdAt, decoded.items.single().createdAt)
        assertEquals(source.modifiedAt, decoded.items.single().modifiedAt)
        assertEquals(1_750_000_000, Json.parseToJsonElement(encoded).jsonObject["timestamp"]!!.jsonPrimitive.long)
        assertFalse(source.toString().contains("alice"))
        assertFalse(source.logins.single().toString().contains("alice"))
    }

    @Test fun requiredRootShapeErrorsAreRejectedConsistently() {
        val invalid = listOf("not json", "[]", "{}",
            """{"version":{"major":[],"minor":0},"accounts":[]}""",
            """{"version":{"major":2,"minor":0},"accounts":[]}""",
            """{"version":{"major":1,"minor":0},"exporterRpId":"test","exporterDisplayName":"test","timestamp":{},"accounts":[]}""")
        invalid.forEach { document ->
            assertTrue(runCatching { CxfCredentialCodec.decode(document) }.exceptionOrNull() is CxfCredentialCodec.InvalidDocument)
        }
    }

    @Test fun unknownOptionalFieldsAndMinorVersionsDoNotBlockTheImport() {
        val encoded = CxfCredentialCodec.encode(listOf(loginItem()), "test", types)
        val source = Json.parseToJsonElement(encoded).jsonObject.toMutableMap()
        source["version"] = buildJsonObject { put("major", 1); put("minor", 7) }
        source["future-optional-field"] = JsonArray(listOf(JsonPrimitive("anything")))
        assertEquals(1, CxfCredentialCodec.decode(JsonObject(source).toString()).passwordCount)
    }

    @Test fun unsupportedCredentialIsCountedWithoutDroppingAValidPassword() {
        val result = decodeCredentials("""{"type":"future-credential","secret":"not logged"},
            {"type":"basic-auth","username":{"fieldType":"string","value":"Alice"},"password":{"fieldType":"concealed-string","value":"secret"}}""")
        assertEquals(1, result.passwordCount)
        assertEquals(1, result.skipped[CxfCredentialCodec.SkipReason.UNSUPPORTED_CREDENTIAL])
    }

    @Test fun corruptPrivateKeyIsSkippedWithoutReplacingIt() {
        val key = keyCredential(ecKey()).toMutableMap()
        key["key"] = JsonPrimitive(CxfCredentialCodec.base64Url(ByteArray(32)))
        val result = decodeCredentials(JsonObject(key).toString())
        assertEquals(0, result.passkeyCount)
        assertEquals(1, result.skippedCount)
    }

    @Test fun malformedOptionalPasswordDoesNotImportAPartialLogin() {
        for (password in listOf("null", "[]", "{}", "123", "\"secret\"", """{"fieldType":"concealed-string","value":123}""")) {
            val decoded = decodeCredentials("""{"type":"basic-auth","username":{"fieldType":"string","value":"Alice"},"password":$password}""")
            assertEquals(0, decoded.passwordCount)
            assertEquals(1, decoded.skippedCount)
        }
        assertEquals(1, decodeCredentials("""{"type":"basic-auth","username":{"fieldType":"string","value":"Alice"}}""").passwordCount)
    }

    @Test fun requiredNumericFieldsDoNotAcceptNumericStrings() {
        val good = Json.parseToJsonElement(CxfCredentialCodec.encode(listOf(loginItem()), "test", types)).jsonObject
        val invalid = listOf(good + ("version" to buildJsonObject { put("major", "1"); put("minor", 0) }),
            good + ("timestamp" to JsonPrimitive("1700000000")))
        invalid.forEach { assertTrue(runCatching { CxfCredentialCodec.decode(JsonObject(it).toString()) }.isFailure) }
    }

    @Test fun prfOrOtherUnimplementedExtensionsNeverLoseTheirSeedSilently() {
        for (extensions in listOf("""{"hmacCredentials":[{"credWithUV":"AQID"}]}""", "[]", "null")) {
            val key = keyCredential(ecKey()).toMutableMap()
            key["fido2Extensions"] = Json.parseToJsonElement(extensions)
            val decoded = decodeCredentials(JsonObject(key).toString())
            assertEquals(0, decoded.passkeyCount)
            assertEquals(1, decoded.skipped[CxfCredentialCodec.SkipReason.PASSKEY_EXTENSION])
        }
    }

    @Test fun rpIdAndOpaqueIdsAreNotReplacedByTitlesOrUrls() {
        val key = keyCredential(ecKey())
        val decoded = decodeCredentials(key.toString()).items.single().passkeys.single()
        assertEquals("login.example.com", decoded.rpId)
        assertEquals("AAEC_w", decoded.credentialId)
        assertEquals("__79AQ", decoded.userHandle)
        assertEquals(0L, decoded.signCount)
    }

    @Test fun invalidRpAndOversizedHandleAreSkipped() {
        val original = keyCredential(ecKey())
        for ((field, value) in listOf("rpId" to "https://example.com", "userHandle" to "", "credentialId" to "!@#",
            "userHandle" to CxfCredentialCodec.base64Url(ByteArray(65)))) {
            val decoded = decodeCredentials(JsonObject(original + (field to JsonPrimitive(value))).toString())
            assertEquals(0, decoded.passkeyCount)
            assertEquals(1, decoded.skippedCount)
        }
    }

    @Test fun nonzeroCountersAreExcludedWithoutChangingTheOriginalEntry() {
        val imported = decodeCredentials(keyCredential(ecKey()).toString()).items.single()
        val nonzero = imported.passkeys.single().copy(signCount = 5)
        val source = imported.copy(passkeys = listOf(nonzero), logins = loginItem().logins)
        val result = CxfCredentialCodec.decode(CxfCredentialCodec.encode(listOf(source), "test", types))
        assertEquals(1, result.passwordCount)
        assertEquals(0, result.passkeyCount)
        assertEquals(5L, nonzero.signCount)
        assertEquals(0, CxfCredentialCodec.decode(CxfCredentialCodec.encode(listOf(imported.copy(passkeys = listOf(nonzero))), "test", types)).items.size)
    }

    @Test fun exporterRespectsRequestedCredentialTypes() {
        val imported = decodeCredentials(keyCredential(ecKey()).toString()).items.single().copy(logins = loginItem().logins)
        val passwordsOnly = CxfCredentialCodec.decode(CxfCredentialCodec.encode(listOf(imported), "test", setOf("basic-auth")))
        assertEquals(1, passwordsOnly.passwordCount)
        assertEquals(0, passwordsOnly.passkeyCount)
        val keysOnly = CxfCredentialCodec.decode(CxfCredentialCodec.encode(listOf(imported), "test", setOf("passkey")))
        assertEquals(0, keysOnly.passwordCount)
        assertEquals(1, keysOnly.passkeyCount)
    }

    @Test fun p256PrivateKeySurvivesExchangeAndSignsForTheOriginalPublicKey() = checkSignature(ecKey(), "EC", "SHA256withECDSA", -7)
    @Test fun rsaPrivateKeySurvivesExchangeAndSignsForTheOriginalPublicKey() = checkSignature(
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(), "RSA", "SHA256withRSA", -257)
    @Test fun ed25519PrivateKeySurvivesExchangeAndSignsForTheOriginalPublicKey() = checkSignature(
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), "Ed25519", "Ed25519", -8)

    @Test fun unsupportedCurvesAndWeakRsaKeysAreRejected() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val rsa1024 = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertNull(CxfPasskeyMaterial.decode(p384.private.encoded))
        assertNull(CxfPasskeyMaterial.decode(rsa1024.private.encoded))
    }

    private fun checkSignature(pair: KeyPair, keyType: String, signatureType: String, algorithm: Int) {
        val item = decodeCredentials(keyCredential(pair).toString()).items.single()
        val encoded = CxfCredentialCodec.encode(listOf(item), "test", types)
        val key = CxfCredentialCodec.decode(encoded).items.single().passkeys.single()
        val encodedKey = Base64.getUrlDecoder().decode(key.key)
        assertArrayEquals(pair.private.encoded, encodedKey)
        assertEquals(algorithm, key.algorithm)
        val challenge = "webauthn authenticatorData + clientDataHash; synthetic test".toByteArray()
        val restored = KeyFactory.getInstance(keyType).generatePrivate(PKCS8EncodedKeySpec(encodedKey))
        val signature = Signature.getInstance(signatureType).apply { initSign(restored); update(challenge) }.sign()
        assertTrue(Signature.getInstance(signatureType).apply { initVerify(pair.public); update(challenge) }.verify(signature))
        // Inspect derived COSE coordinates with an independent decoder.
        val cose = readCose(Base64.getDecoder().decode(key.publicKey))
        assertEquals(algorithm, cose[3])
        when (val publicKey = pair.public) {
            is ECPublicKey -> {
                assertEquals(publicKey.w.affineX, java.math.BigInteger(1, cose[-2] as ByteArray))
                assertEquals(publicKey.w.affineY, java.math.BigInteger(1, cose[-3] as ByteArray))
            }
            is RSAPublicKey -> {
                assertEquals(publicKey.modulus, java.math.BigInteger(1, cose[-1] as ByteArray))
                assertEquals(publicKey.publicExponent, java.math.BigInteger(1, cose[-2] as ByteArray))
            }
            else -> assertArrayEquals(pair.public.encoded.takeLast(32).toByteArray(), cose[-2] as ByteArray)
        }
    }

    private fun readCose(bytes: ByteArray): Map<Int, Any> {
        val input = bytes.inputStream()
        fun value(): Any {
            val header = input.read()
            val major = header ushr 5
            val length = when (val ai = header and 31) {
                24 -> input.read()
                25 -> (input.read() shl 8) or input.read()
                else -> ai
            }
            return when (major) { 0 -> length; 1 -> -1 - length; 2 -> input.readNBytes(length); else -> error("Unexpected CBOR type") }
        }
        val count = input.read() and 31
        return buildMap { repeat(count) { put(value() as Int, value()) }; assertEquals(-1, input.read()) }
    }

    private fun ecKey() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun loginItem() = CxfCredentialCodec.Item("1", "test", logins = listOf(CxfCredentialCodec.Login("alice", "secret")), createdAt = 1_700_000_000_000)
    private fun keyCredential(pair: KeyPair) = buildJsonObject {
        put("type", "passkey"); put("credentialId", "AAEC_w"); put("rpId", "login.example.com")
        put("username", "alice"); put("userDisplayName", "Alice"); put("userHandle", "__79AQ")
        put("key", CxfCredentialCodec.base64Url(pair.private.encoded))
    }
    private fun decodeCredentials(credentials: String) = CxfCredentialCodec.decode("""{
        "version":{"major":1,"minor":0},"exporterRpId":"test.example.com","exporterDisplayName":"Test","timestamp":1700000000,
        "accounts":[{"id":"AQ","username":"Alice","email":"a@example.com","collections":[],"items":[{
        "id":"Ag","title":"A misleading title","scope":{"urls":["https://unrelated.example.com"],"androidApps":[]},
        "credentials":[$credentials]}]}]}""")
}
