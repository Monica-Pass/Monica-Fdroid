package takagi.ru.monica.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableTotpBackupCodecTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decrypts a whole encrypted TOTP payload`() {
        val encrypted = "MDK|outer"
        val result = PortableTotpBackupCodec.encode(encrypted, "GitHub") { value ->
            if (value == encrypted) "{\"secret\":\"ABC123\",\"issuer\":\"GitHub\"}" else value
        }

        assertEquals("ABC123", result.field("secret"))
        assertEquals("GitHub", result.field("issuer"))
    }

    @Test
    fun `decrypts nested TOTP and Steam sensitive fields without dropping metadata`() {
        val input = """{"secret":"MDK|secret","pin":"MDK|pin","steamSharedSecretBase64":"MDK|shared","steamRevocationCode":"MDK|revocation","steamIdentitySecret":"MDK|identity","steamRawJson":"MDK|raw","futureField":"kept"}"""
        val result = PortableTotpBackupCodec.encode(input, "Steam") { value ->
            if (value.startsWith("MDK|")) value.removePrefix("MDK|") else value
        }

        assertEquals("secret", result.field("secret"))
        assertEquals("pin", result.field("pin"))
        assertEquals("shared", result.field("steamSharedSecretBase64"))
        assertEquals("revocation", result.field("steamRevocationCode"))
        assertEquals("identity", result.field("steamIdentitySecret"))
        assertEquals("raw", result.field("steamRawJson"))
        assertEquals("kept", result.field("futureField"))
        assertFalse(result.contains("MDK|"))
    }

    @Test
    fun `rejects an unreadable nested secret`() {
        assertThrows(PortableSecretExportException::class.java) {
            PortableTotpBackupCodec.encode("{\"secret\":\"MDK|lost\"}", "Account") { value ->
                if (value.startsWith("MDK|")) throw IllegalStateException("key unavailable")
                value
            }
        }
    }

    @Test
    fun `preserves legacy plaintext secret payloads`() {
        assertEquals(
            "JBSWY3DPEHPK3PXP",
            PortableTotpBackupCodec.encode("JBSWY3DPEHPK3PXP", "Legacy") { it }
        )
    }

    private fun String.field(name: String): String =
        json.parseToJsonElement(this).jsonObject.getValue(name).jsonPrimitive.content
}
