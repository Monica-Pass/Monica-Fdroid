package takagi.ru.monica.util

import takagi.ru.monica.localization.xmlTestStrings

import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.utils.KeePassCredentialSupport
import java.util.Base64

class KeePassCredentialSupportTest {
    @Test
    fun raw32ByteKeyRemainsUnchangedAndEachCandidateUsesTheOriginalBytes() {
        for (password in listOf("", "synthetic-password")) {
            val key = ByteArray(32) { (it + 7).toByte() }
            val original = key.copyOf()
            repeat(3) {
                val exact = KeePassCredentialSupport.buildExactCredentials(password, key)
                org.junit.Assert.assertArrayEquals(original, exact.key!!.getBinary())
                org.junit.Assert.assertArrayEquals(original, key)
                val candidates = KeePassCredentialSupport.buildCredentialCandidates(password, key)
                candidates.filter { it.label.startsWith("raw/") }.forEach {
                    org.junit.Assert.assertArrayEquals(it.label, original, it.credentials.key!!.getBinary())
                }
                org.junit.Assert.assertArrayEquals(original, key)
            }
        }
    }

    @Test
    fun noKeyFile_buildsPasswordOnlyCandidate() {
        val candidates = KeePassCredentialSupport.buildCredentialCandidates(
            password = "demo",
            keyFileBytes = null
        )
        assertTrue(candidates.size == 1)
        assertTrue(candidates.first().label == "password-only")
    }

    @Test
    fun xmlKeyFile_buildsXmlVariantCandidates() {
        val rawKey = ByteArray(32) { (it + 1).toByte() }
        val b64 = Base64.getEncoder().encodeToString(rawKey)
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <KeyFile><Key><Data>$b64</Data></Key></KeyFile>
        """.trimIndent().toByteArray()

        val candidates = KeePassCredentialSupport.buildCredentialCandidates(
            password = "",
            keyFileBytes = xml
        )
        val labels = candidates.map { it.label }
        assertTrue(labels.any { it.startsWith("xml-data/") })
    }

    @Test
    fun hexKeyFile_buildsHexVariantCandidates() {
        val hex = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF".toByteArray()
        val candidates = KeePassCredentialSupport.buildCredentialCandidates(
            password = "",
            keyFileBytes = hex
        )
        val labels = candidates.map { it.label }
        assertTrue(labels.any { it.startsWith("hex-text/") })
    }

    @Test
    fun invalidCredentialMessage_containsAttemptSummary() {
        val message = KeePassCredentialSupport.buildInvalidCredentialMessage(
            listOf("raw/password+key", "xml-data/password+key"),
            strings = xmlTestStrings("zh"),
        )
        assertTrue(message.contains("已尝试"))
        assertTrue(message.contains("raw/password+key"))
    }
}
