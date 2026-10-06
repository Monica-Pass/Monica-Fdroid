package takagi.ru.monica.passkey

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PasskeyCredentialIdCodecTest {
    @Test fun originalBytesSurviveAllSupportedCarriers() {
        for (length in listOf(1, 2, 3, 16, 32, 64, 128)) {
            val bytes = ByteArray(length) { (it * 17 + 251).toByte() }
            val url = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val forms = mutableListOf(url, Base64.getEncoder().encodeToString(bytes), "b64.$url")
            if (length == 16) forms += requireNotNull(PasskeyCredentialIdCodec.normalize(url))
            for (form in forms) {
                assertEquals(url, PasskeyCredentialIdCodec.toWebAuthnId(form))
                val exported = requireNotNull(PasskeyCredentialIdCodec.toBitwardenCredentialId(form))
                assertEquals(url, PasskeyCredentialIdCodec.toWebAuthnId(exported))
                assertEquals(length != 16, exported.startsWith("b64."))
                assertArrayEquals(bytes, Base64.getUrlDecoder().decode(PasskeyCredentialIdCodec.toWebAuthnId(exported)))
            }
        }
    }

    @Test fun actualEdgeRegisteredIdIsNotReinterpretedAsPrefixBytes() {
        val id = "sPRk4p1Z8GKaHlkk_iwpSRZ5B9HeqPZNWg5optdhDfI"
        assertEquals(id, PasskeyCredentialIdCodec.toWebAuthnId("b64.$id"))
        assertEquals("b64.$id", PasskeyCredentialIdCodec.toBitwardenCredentialId(id))
        val alreadyCorrupted = "b64sPRk4p1Z8GKaHlkk_iwpSRZ5B9HeqPZNWg5optdhDfA"
        assertEquals(alreadyCorrupted, PasskeyCredentialIdCodec.toWebAuthnId(alreadyCorrupted))
    }

    @Test fun malformedLegacyTextIsPreservedAndCannotAuthorizeARequest() {
        for (id in listOf("b64.", "b64..AA", "bw_ref_cipher", "a", "AB", "AA=", "AA===", "AAAA=", "A A", "1-1-1-1-1", "AA+_")) {
            assertEquals(id, PasskeyCredentialIdCodec.normalize(id))
            assertEquals(id, PasskeyCredentialIdCodec.toWebAuthnId(id))
            assertEquals(id, PasskeyCredentialIdCodec.toBitwardenCredentialId(id))
            assertFalse(id, PasskeyCredentialIdCodec.isValid(id))
        }
        assertNull(PasskeyCredentialIdCodec.normalize(" "))
    }

    @Test fun uuidRepresentationRetainsNetworkByteOrder() {
        val uuid = "00112233-4455-6677-8899-aabbccddeeff"
        assertEquals("ABEiM0RVZneImaq7zN3u_w", PasskeyCredentialIdCodec.toWebAuthnId(uuid))
        assertEquals(uuid, PasskeyCredentialIdCodec.normalize("b64.ABEiM0RVZneImaq7zN3u_w"))
    }
}
