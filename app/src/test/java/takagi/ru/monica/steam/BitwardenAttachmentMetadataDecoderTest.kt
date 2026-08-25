package takagi.ru.monica.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenAttachmentMetadataDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BitwardenAttachmentMetadataDecoderTest {
    @Test
    fun encryptedAttachmentNameIsDecryptedBeforeLocalReconciliation() {
        val key = testKey()
        val encryptedName = encryptFixture("account.maFile", key)

        val decoded = BitwardenAttachmentMetadataDecoder.decodeForStorage(
            attachments = listOf(
                CipherAttachmentApiData(
                    id = "attachment-id",
                    fileName = encryptedName,
                    size = "5120"
                )
            ),
            effectiveKey = key
        )

        assertEquals("account.maFile", decoded.single().fileName)
    }

    @Test
    fun legacyPlainAttachmentNameIsPreserved() {
        val decoded = BitwardenAttachmentMetadataDecoder.decodeForStorage(
            attachments = listOf(
                CipherAttachmentApiData(
                    id = "attachment-id",
                    fileName = "account.maFile",
                    size = "5120"
                )
            ),
            effectiveKey = testKey()
        )

        assertEquals("account.maFile", decoded.single().fileName)
    }

    @Test
    fun undecryptableCipherNameDoesNotReplaceAUsableLocalName() {
        val decoded = BitwardenAttachmentMetadataDecoder.decodeForStorage(
            attachments = listOf(
                CipherAttachmentApiData(
                    id = "attachment-id",
                    fileName = "2.aW52YWxpZA==|Y2lwaGVydGV4dA==|aW52YWxpZG1hYw==",
                    size = "5120"
                )
            ),
            effectiveKey = testKey()
        )

        assertNull(decoded.single().fileName)
    }

    private fun testKey(): SymmetricCryptoKey = SymmetricCryptoKey(
        encKey = ByteArray(32) { index -> (index + 1).toByte() },
        macKey = ByteArray(32) { index -> (index + 33).toByte() }
    )

    private fun encryptFixture(
        plaintext: String,
        key: SymmetricCryptoKey
    ): String {
        val iv = ByteArray(16) { index -> (index + 65).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key.macKey, "HmacSHA256"))
            update(iv)
            update(encrypted)
        }.doFinal()
        val encoder = Base64.getEncoder()
        return "2.${encoder.encodeToString(iv)}|${encoder.encodeToString(encrypted)}|${encoder.encodeToString(mac)}"
    }
}
