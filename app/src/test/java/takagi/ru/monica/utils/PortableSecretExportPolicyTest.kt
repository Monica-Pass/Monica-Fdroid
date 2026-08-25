package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableSecretExportPolicyTest {
    @Test
    fun `returns plaintext from a compatible decryptor`() {
        assertEquals(
            "secret",
            PortableSecretExportPolicy.resolve("MDK|cipher", "Account") { "secret" }
        )
    }

    @Test
    fun `keeps an already portable value`() {
        assertEquals(
            "secret",
            PortableSecretExportPolicy.resolve("secret", "Account") { it }
        )
    }

    @Test
    fun `does not fall back to installation bound ciphertext`() {
        assertThrows(PortableSecretExportException::class.java) {
            PortableSecretExportPolicy.resolve("MDK|cipher", "Account") {
                throw IllegalStateException("key unavailable")
            }
        }
    }
}
