package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeePassPortableSecretExportPolicyTest {
    @Test
    fun `exports the decrypted secret instead of stored ciphertext`() {
        val result = KeePassPortableSecretExportPolicy.resolve(
            storedValue = "MONICA_MDK_ciphertext",
            entryTitle = "Example",
            decrypt = { "portable-password" }
        )

        assertEquals("portable-password", result)
    }

    @Test
    fun `keeps plaintext returned by the compatible decryptor`() {
        val result = KeePassPortableSecretExportPolicy.resolve(
            storedValue = "already-plain",
            entryTitle = "Example",
            decrypt = { it }
        )

        assertEquals("already-plain", result)
    }

    @Test
    fun `never falls back to device ciphertext when decryption fails`() {
        val error = assertThrows(KeePassPortableSecretExportException::class.java) {
            KeePassPortableSecretExportPolicy.resolve(
                storedValue = "MONICA_MDK_unreadable",
                entryTitle = "Depth Studio",
                decrypt = { throw IllegalStateException("MDK not available") }
            )
        }

        assertEquals("Depth Studio", error.entryTitle)
    }
}
