package takagi.ru.monica.attachments.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

class BitwardenAttachmentCryptoTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SymmetricCryptoKey(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })

    @Test
    fun readsOfficialBinaryAttachmentFormat() {
        val plaintext = "Synthetic Bitwarden attachment\n".toByteArray()
        val decrypted = ByteArrayOutputStream()

        BitwardenAttachmentCrypto.decryptFile(cipherFile(officialPayload(plaintext)), decrypted, key)

        assertArrayEquals(plaintext, decrypted.toByteArray())
    }

    @Test
    fun uploadsAreReadableUsingOfficialBinaryLayout() {
        val plaintext = "Synthetic attachment created in Monica".toByteArray()
        val encrypted = temporary.newFile()
        BitwardenAttachmentCrypto.encryptFile(ByteArrayInputStream(plaintext), encrypted, key)
        val payload = encrypted.readBytes()

        assertEquals("Bitwarden encryption type byte", 2, payload[0].toInt())
        val iv = payload.copyOfRange(1, 17)
        val expectedMac = payload.copyOfRange(17, 49)
        val ciphertext = payload.copyOfRange(49, payload.size)
        assertArrayEquals(expectedMac, hmac(iv, ciphertext))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        }
        assertArrayEquals(plaintext, cipher.doFinal(ciphertext))
    }

    @Test
    fun readsEmptyAndMultiChunkOfficialAttachments() {
        listOf(0, 1, 15, 16, 17, 16_384, 32_799, 1_048_593).forEach { size ->
            val plaintext = ByteArray(size) { (it * 31).toByte() }
            val decrypted = ByteArrayOutputStream()
            val result = BitwardenAttachmentCrypto.decryptFile(cipherFile(officialPayload(plaintext)), decrypted, key)
            assertArrayEquals(plaintext, decrypted.toByteArray())
            assertEquals(size.toLong(), result.plainSizeBytes)
        }
    }

    @Test
    fun oldMonicaUploadsRemainReadableEvenWhenIvLooksLikeATypeByte() {
        listOf(0, 2, 7, 255).forEach { firstByte ->
            val plaintext = ByteArray(32_800) { it.toByte() }
            val iv = ByteArray(16) { (it + firstByte).toByte() }
            val official = officialPayload(plaintext, iv)
            val oldPayload = iv + official.copyOfRange(49, official.size) + official.copyOfRange(17, 49)
            val decrypted = ByteArrayOutputStream()
            BitwardenAttachmentCrypto.decryptFile(cipherFile(oldPayload), decrypted, key)
            assertArrayEquals(plaintext, decrypted.toByteArray())
        }
    }

    @Test
    fun corruptedIvMacOrCiphertextNeverReleasesPlaintext() {
        val payload = officialPayload(ByteArray(65_600) { it.toByte() })
        listOf(1, 17, 49, payload.lastIndex).forEach { position ->
            val damaged = payload.copyOf()
            damaged[position] = (damaged[position].toInt() xor 1).toByte()
            assertRejectedWithoutPlaintext(damaged)
        }
    }

    @Test
    fun truncatedAndMalformedAttachmentsNeverReleasePlaintext() {
        val payload = officialPayload(ByteArray(32_800))
        listOf(0, 1, 16, 17, 32, 48, 49, 63, 64, payload.size - 1, payload.size - 16).forEach {
            assertRejectedWithoutPlaintext(payload.copyOf(it))
        }
        assertRejectedWithoutPlaintext(payload + byteArrayOf(0))
    }

    @Test
    fun wrongAttachmentKeyDoesNotReleasePlaintext() {
        val encrypted = cipherFile(officialPayload(ByteArray(50_000)))
        val wrongKey = SymmetricCryptoKey(ByteArray(32) { 42 }, ByteArray(32) { 43 })
        val output = ByteArrayOutputStream()
        val failure = runCatching { BitwardenAttachmentCrypto.decryptFile(encrypted, output, wrongKey) }.exceptionOrNull()
        assertTrue(failure is GeneralSecurityException)
        assertEquals(0, output.size())
    }

    @Test
    fun doesNotDowngradeToUnauthenticatedCbc() {
        val official = officialPayload(ByteArray(50_000))
        assertRejectedWithoutPlaintext(byteArrayOf(0) + official.copyOfRange(1, 17) + official.copyOfRange(49, official.size))
        assertRejectedWithoutPlaintext(official.copyOf().also { it[0] = 7 })
    }

    @Test
    fun rejectsOversizedCiphertextBeforeReadingIt() {
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(BitwardenAttachmentCrypto.MAX_ENCRYPTED_BYTES + 1) }
        val failure = runCatching { BitwardenAttachmentCrypto.openDecrypted(file, key) }.exceptionOrNull()
        assertTrue(failure is takagi.ru.monica.attachments.model.AttachmentError.TooLarge)
    }

    @Test
    fun missingLegacyAttachmentKeyUsesOwnedCopyOfCipherKey() {
        val original = key.encKey.copyOf()
        val resolved = BitwardenAttachmentCrypto.unwrapAttachmentKey(null, key)
        assertEquals(key, resolved)
        resolved.clear()
        assertArrayEquals(original, key.encKey)
    }

    @Test
    fun malformedExplicitAttachmentKeyNeverFallsBackToCipherKey() {
        assertNotNull(runCatching { BitwardenAttachmentCrypto.unwrapAttachmentKey("invalid-key", key) }.exceptionOrNull())
    }

    private fun cipherFile(payload: ByteArray): File = temporary.newFile().apply { writeBytes(payload) }

    private fun assertRejectedWithoutPlaintext(payload: ByteArray) {
        val output = ByteArrayOutputStream()
        assertNotNull(runCatching { BitwardenAttachmentCrypto.decryptFile(cipherFile(payload), output, key) }.exceptionOrNull())
        assertEquals("Unauthenticated bytes must not reach preview or local storage", 0, output.size())
    }

    // Bitwarden SDK EncString::to_buffer: type(2) || IV(16) || HMAC(32) || ciphertext.
    // Build this fixture independently of Monica's writer so a symmetric format bug cannot pass.
    private fun officialPayload(plaintext: ByteArray, iv: ByteArray = ByteArray(16) { (it + 11).toByte() }): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        }
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(2) + iv + hmac(iv, ciphertext) + ciphertext
    }

    private fun hmac(iv: ByteArray, ciphertext: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.macKey, "HmacSHA256"))
            update(iv)
            doFinal(ciphertext)
        }
}
