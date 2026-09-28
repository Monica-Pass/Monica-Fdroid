package takagi.ru.monica.util

import org.junit.Assert.*
import org.junit.Test

class TotpUnreadableDataTest {
    @Test fun malformedStructuredSecretDoesNotThrowOrBecomeAKey() {
        assertNull(TotpDataResolver.parseStoredItemData("""{"secret":{"value":"unsupported"}}"""))
    }

    @Test fun failedDecryptionDoesNotTurnCiphertextIntoAnOtpSecret() {
        assertNull(TotpDataResolver.parseStoredItemData("C2|invalid", decryptIfNeeded = {
            throw IllegalStateException("synthetic unavailable key")
        }))
    }

    @Test fun encryptedDataWithoutADecryptorIsNotAPlainKey() {
        listOf("C2|invalid", "V2|invalid", "MDK|invalid").forEach {
            assertNull(TotpDataResolver.fromAuthenticatorKey(it))
        }
    }

    @Test fun retryAfterUnlockReadsTheSameStoredPayload() {
        var unlocked = false
        val decrypt: (String) -> String = {
            check(unlocked)
            """{"secret":"JBSWY3DPEHPK3PXP","issuer":"Example"}"""
        }
        assertNull(TotpDataResolver.parseStoredItemData("C2|fixture", decryptIfNeeded = decrypt))
        unlocked = true
        assertEquals("JBSWY3DPEHPK3PXP", TotpDataResolver.parseStoredItemData("C2|fixture", decryptIfNeeded = decrypt)?.secret)
    }
}
