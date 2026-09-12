package takagi.ru.monica.attachments.crypto

import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

/**
 * Bitwarden EncString binary format: type(2) || IV(16) || HMAC(32) || AES-CBC ciphertext.
 * HMAC-SHA256 authenticates IV || ciphertext. Older Monica uploads instead used
 * IV || ciphertext || HMAC; those remain readable after authenticating the whole file.
 *
 * Files allow a bounded-memory authentication pass before releasing any plaintext and let the
 * writer fill in the leading MAC without buffering an entire attachment in memory.
 */
object BitwardenAttachmentCrypto {
    private const val TYPE_AES_CBC_HMAC = 2
    private const val IV_SIZE = 16
    private const val MAC_SIZE = 32
    private const val HEADER_SIZE = 1 + IV_SIZE + MAC_SIZE
    private const val BUFFER_SIZE = 16 * 1024
    const val MAX_PLAINTEXT_BYTES = 100L * 1024 * 1024
    const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + HEADER_SIZE + IV_SIZE

    private val rng: SecureRandom by lazy { SecureRandom() }

    class UnsupportedFormatException(val encryptionType: Int) :
        GeneralSecurityException("Unsupported Bitwarden attachment encryption type: $encryptionType")

    /** An absent key is legitimate for old attachments encrypted directly with the cipher key. */
    fun unwrapAttachmentKey(fileKeyEnc: String?, wrappingKey: SymmetricCryptoKey): SymmetricCryptoKey {
        if (fileKeyEnc.isNullOrBlank()) return wrappingKey.copyOwned()
        val parsed = BitwardenCrypto.parseCipherString(fileKeyEnc)
        // A 64-byte wrapping key includes a MAC key. Do not accept a stripped-MAC downgrade.
        if (parsed.type != TYPE_AES_CBC_HMAC) throw UnsupportedFormatException(parsed.type)
        val raw = BitwardenCrypto.decrypt(parsed, wrappingKey)
        return try {
            if (raw.size != 64) throw GeneralSecurityException("Invalid Bitwarden attachment key length")
            SymmetricCryptoKey(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
        } finally {
            raw.fill(0)
        }
    }

    fun generateAndWrapAttachmentKey(wrappingKey: SymmetricCryptoKey): Pair<SymmetricCryptoKey, String> {
        val raw = ByteArray(64).also(rng::nextBytes)
        return try {
            // Wrap before allocating the returned key, so a failed wrap leaves no extra key copy.
            val encrypted = BitwardenCrypto.encrypt(raw, wrappingKey)
            SymmetricCryptoKey(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64)) to encrypted
        } finally {
            raw.fill(0)
        }
    }

    fun encryptFile(source: InputStream, target: File, attachmentKey: SymmetricCryptoKey): StreamResult {
        val iv = ByteArray(IV_SIZE).also(rng::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, attachmentKey, iv)
        val mac = mac(attachmentKey).apply { update(iv) }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        try {
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0)
                out.write(TYPE_AES_CBC_HMAC)
                out.write(iv)
                out.write(ByteArray(MAC_SIZE))
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    if (size > MAX_PLAINTEXT_BYTES) throw AttachmentError.TooLarge(MAX_PLAINTEXT_BYTES, size)
                    digest.update(buffer, 0, count)
                    cipher.update(buffer, 0, count)?.let { encrypted ->
                        out.write(encrypted)
                        mac.update(encrypted)
                    }
                }
                val tail = cipher.doFinal()
                out.write(tail)
                mac.update(tail)
                out.seek((1 + IV_SIZE).toLong())
                out.write(mac.doFinal())
            }
            return StreamResult(size, digest.digest().toHex())
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            buffer.fill(0)
        }
    }

    /** Verifies the entire encrypted file before returning a stream containing any plaintext. */
    fun openDecrypted(source: File, attachmentKey: SymmetricCryptoKey): InputStream {
        val layout = readAndAuthenticate(source, attachmentKey)
        val input = source.inputStream().buffered(BUFFER_SIZE)
        return try {
            var remaining = layout.ciphertextOffset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) throw EOFException("Bitwarden attachment header truncated")
                remaining -= skipped
            }
            CipherInputStream(
                LimitedInputStream(input, layout.ciphertextSize),
                cipher(Cipher.DECRYPT_MODE, attachmentKey, layout.iv)
            )
        } catch (error: Exception) {
            input.close()
            throw error
        }
    }

    fun decryptFile(source: File, sink: OutputStream, attachmentKey: SymmetricCryptoKey): StreamResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        try {
            openDecrypted(source, attachmentKey).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    if (size > MAX_PLAINTEXT_BYTES) throw AttachmentError.TooLarge(MAX_PLAINTEXT_BYTES, size)
                    sink.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
            sink.flush()
            return StreamResult(size, digest.digest().toHex())
        } finally {
            buffer.fill(0)
        }
    }

    private data class Layout(val iv: ByteArray, val ciphertextOffset: Long, val ciphertextSize: Long)

    private fun readAndAuthenticate(source: File, key: SymmetricCryptoKey): Layout {
        RandomAccessFile(source, "r").use { input ->
            val size = input.length()
            if (size > MAX_ENCRYPTED_BYTES) throw AttachmentError.TooLarge(MAX_ENCRYPTED_BYTES, size)
            if (size < IV_SIZE * 4L) throw GeneralSecurityException("Bitwarden attachment truncated")
            val iv = ByteArray(IV_SIZE)
            val expectedMac = ByteArray(MAC_SIZE)
            val layout = when {
                size % IV_SIZE == 1L -> {
                    val type = input.readUnsignedByte()
                    if (type != TYPE_AES_CBC_HMAC) throw UnsupportedFormatException(type)
                    input.readFully(iv)
                    input.readFully(expectedMac)
                    Layout(iv, HEADER_SIZE.toLong(), size - HEADER_SIZE)
                }
                size % IV_SIZE == 0L -> {
                    // Identify the old authenticated Monica layout by length, not IV[0], which
                    // may itself be 0, 2, or another encryption type. Never retry without a MAC.
                    input.readFully(iv)
                    input.seek(size - MAC_SIZE)
                    input.readFully(expectedMac)
                    Layout(iv, IV_SIZE.toLong(), size - IV_SIZE - MAC_SIZE)
                }
                else -> throw GeneralSecurityException("Invalid Bitwarden attachment length")
            }
            val mac = mac(key).apply { update(iv) }
            val buffer = ByteArray(BUFFER_SIZE)
            input.seek(layout.ciphertextOffset)
            var remaining = layout.ciphertextSize
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw GeneralSecurityException("Bitwarden attachment truncated")
                mac.update(buffer, 0, count)
                remaining -= count
            }
            val actualMac = mac.doFinal()
            try {
                if (!MessageDigest.isEqual(actualMac, expectedMac)) {
                    throw GeneralSecurityException("Bitwarden attachment MAC verification failed")
                }
            } finally {
                actualMac.fill(0)
            }
            return layout
        }
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = super.read()
            if (value < 0) throw EOFException("Bitwarden attachment ciphertext truncated")
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val count = `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Bitwarden attachment ciphertext truncated")
            remaining -= count
            return count
        }
    }

    private fun cipher(mode: Int, key: SymmetricCryptoKey, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        }

    private fun mac(key: SymmetricCryptoKey): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key.macKey, "HmacSHA256"))
    }

    private fun SymmetricCryptoKey.copyOwned() = SymmetricCryptoKey(encKey.copyOf(), macKey.copyOf())
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    data class StreamResult(val plainSizeBytes: Long, val plainSha256Hex: String)

    fun encryptStringForAttachment(plaintext: String, wrappingKey: SymmetricCryptoKey): String =
        BitwardenCrypto.encryptString(plaintext, wrappingKey)
}
