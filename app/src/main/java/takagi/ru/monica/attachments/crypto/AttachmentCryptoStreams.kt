package takagi.ru.monica.attachments.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 附件本地密文的 AES-256-GCM 流式加解密工厂。
 *
 * 新写入附件使用分块认证格式，避免旧版 Android 的 AES-GCM 实现缓存完整明文：
 *
 * ```
 * MONATT2\0 | chunkSize | baseNonce | (plainLength | ciphertext | tag)* | 0 | endTag
 * ```
 *
 * 注意：
 * - 这里统一使用 128-bit（16 字节）认证 tag。
 * - 每块使用独立 nonce，并认证块序号和明文长度。
 * - [encryptingStream] 和 [decryptingStream] 仍用于读取旧版 `12B IV + 单段 GCM` 附件。
 *
 * 对应 requirements.md Requirement 2.1 / 2.3 / 2.4。
 */
internal object AttachmentCryptoStreams {

    const val IV_SIZE: Int = 12
    const val TAG_SIZE_BITS: Int = 128
    const val CEK_SIZE_BYTES: Int = 32
    const val CHUNK_SIZE_BYTES: Int = 256 * 1024
    private const val TRANSFORMATION: String = "AES/GCM/NoPadding"
    private const val TAG_SIZE_BYTES: Int = TAG_SIZE_BITS / 8
    private val CHUNKED_MAGIC = byteArrayOf(
        'M'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 'A'.code.toByte(),
        'T'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte(), 0
    )

    private val rng: SecureRandom by lazy { SecureRandom() }

    /** 生成一个 12 字节随机 IV/nonce。 */
    fun newIv(): ByteArray = ByteArray(IV_SIZE).also(rng::nextBytes)

    /** 生成一个 32 字节随机 CEK。 */
    fun newCek(): ByteArray = ByteArray(CEK_SIZE_BYTES).also(rng::nextBytes)

    /**
     * 用 [cek] 和 [iv] 初始化一个加密 [CipherOutputStream]，下游写入的字节会被加密后再写入 [out]。
     *
     * 调用方需要在 `out.write(iv)` 之后再用本函数获取 CipherOutputStream；
     * 这样 IV 不会被加密流吞掉，而是以明文前缀形式留在文件头。
     */
    fun encryptingStream(out: OutputStream, cek: ByteArray, iv: ByteArray): CipherOutputStream {
        require(cek.size == CEK_SIZE_BYTES) { "CEK must be 32 bytes" }
        require(iv.size == IV_SIZE) { "IV must be 12 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return CipherOutputStream(out, cipher)
    }

    /**
     * 用 [cek] 和 [iv] 初始化一个解密 [CipherInputStream]，从上游读到的字节为密文。
     *
     * 调用方需要已经把 IV 从 [src] 的前 12 字节读掉并传进来。
     */
    fun decryptingStream(src: InputStream, cek: ByteArray, iv: ByteArray): CipherInputStream {
        require(cek.size == CEK_SIZE_BYTES) { "CEK must be 32 bytes" }
        require(iv.size == IV_SIZE) { "IV must be 12 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return CipherInputStream(src, cipher)
    }

    fun isChunkedMagic(bytes: ByteArray): Boolean = bytes.contentEquals(CHUNKED_MAGIC)

    fun chunkedMagicSize(): Int = CHUNKED_MAGIC.size

    fun writeChunkedEncrypted(
        source: InputStream,
        out: OutputStream,
        cek: ByteArray,
        onPlaintext: (ByteArray, Int) -> Unit = { _, _ -> }
    ): Long {
        require(cek.size == CEK_SIZE_BYTES) { "CEK must be 32 bytes" }
        val dataOut = DataOutputStream(out)
        val baseNonce = newIv().also { nonce ->
            for (index in NONCE_COUNTER_OFFSET until nonce.size) nonce[index] = 0
        }
        dataOut.write(CHUNKED_MAGIC)
        dataOut.writeInt(CHUNK_SIZE_BYTES)
        dataOut.write(baseNonce)

        val plainBuffer = ByteArray(CHUNK_SIZE_BYTES)
        var total = 0L
        var counter = 0
        while (true) {
            val count = source.readChunk(plainBuffer)
            if (count == 0) break
            onPlaintext(plainBuffer, count)
            val encrypted = encryptChunk(cek, baseNonce, counter, plainBuffer, count)
            dataOut.writeInt(count)
            dataOut.write(encrypted)
            total += count
            counter = nextCounter(counter)
        }

        val endTag = encryptChunk(cek, baseNonce, counter, EMPTY_BYTES, 0)
        dataOut.writeInt(0)
        dataOut.write(endTag)
        dataOut.flush()
        return total
    }

    fun chunkedDecryptingStream(srcAfterMagic: InputStream, cek: ByteArray): InputStream {
        require(cek.size == CEK_SIZE_BYTES) { "CEK must be 32 bytes" }
        return ChunkedAeadInputStream(srcAfterMagic, cek.copyOf())
    }

    private fun encryptChunk(
        cek: ByteArray,
        baseNonce: ByteArray,
        counter: Int,
        plaintext: ByteArray,
        length: Int
    ): ByteArray {
        val cipher = newChunkCipher(Cipher.ENCRYPT_MODE, cek, baseNonce, counter, length)
        return cipher.doFinal(plaintext, 0, length)
    }

    private fun decryptChunk(
        cek: ByteArray,
        baseNonce: ByteArray,
        counter: Int,
        ciphertext: ByteArray,
        plainLength: Int
    ): ByteArray {
        val cipher = newChunkCipher(Cipher.DECRYPT_MODE, cek, baseNonce, counter, plainLength)
        return cipher.doFinal(ciphertext)
    }

    private fun newChunkCipher(
        mode: Int,
        cek: ByteArray,
        baseNonce: ByteArray,
        counter: Int,
        plainLength: Int
    ): Cipher {
        val nonce = baseNonce.copyOf()
        ByteBuffer.wrap(nonce, NONCE_COUNTER_OFFSET, Int.SIZE_BYTES).putInt(counter)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            updateAAD(chunkAad(counter, plainLength))
        }
    }

    private fun chunkAad(counter: Int, plainLength: Int): ByteArray =
        ByteBuffer.allocate(CHUNKED_MAGIC.size + Int.SIZE_BYTES * 2)
            .put(CHUNKED_MAGIC)
            .putInt(counter)
            .putInt(plainLength)
            .array()

    private fun InputStream.readChunk(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            if (read == 0) continue
            total += read
        }
        return total
    }

    private fun nextCounter(counter: Int): Int {
        if (counter == Int.MAX_VALUE) throw IOException("Attachment has too many encrypted chunks")
        return counter + 1
    }

    private class ChunkedAeadInputStream(
        source: InputStream,
        private val cek: ByteArray
    ) : InputStream() {
        private val input = DataInputStream(source)
        private val chunkSize = input.readInt().also { size ->
            if (size !in 1..MAX_CHUNK_SIZE_BYTES) {
                throw IOException("Invalid encrypted attachment chunk size")
            }
        }
        private val baseNonce = ByteArray(IV_SIZE).also(input::readFully)
        private var plaintext = EMPTY_BYTES
        private var position = 0
        private var counter = 0
        private var finished = false
        private var closed = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("Stream is closed")
            if (length == 0) return 0
            if (position >= plaintext.size && !loadNextChunk()) return -1
            val count = minOf(length, plaintext.size - position)
            plaintext.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        private fun loadNextChunk(): Boolean {
            if (finished) return false
            val plainLength = try {
                input.readInt()
            } catch (error: IOException) {
                throw IOException("Encrypted attachment is truncated", error)
            }
            if (plainLength !in 0..chunkSize) {
                throw IOException("Invalid encrypted attachment frame length")
            }
            val encrypted = ByteArray(plainLength + TAG_SIZE_BYTES)
            try {
                input.readFully(encrypted)
            } catch (error: IOException) {
                throw IOException("Encrypted attachment frame is truncated", error)
            }
            val decrypted = try {
                decryptChunk(cek, baseNonce, counter, encrypted, plainLength)
            } catch (error: Exception) {
                throw IOException("Encrypted attachment authentication failed", error)
            }
            if (decrypted.size != plainLength) {
                throw IOException("Invalid encrypted attachment plaintext length")
            }
            counter = nextCounter(counter)
            if (plainLength == 0) {
                if (input.read() != -1) {
                    throw IOException("Encrypted attachment has trailing data")
                }
                finished = true
                plaintext = EMPTY_BYTES
                position = 0
                return false
            }
            plaintext = decrypted
            position = 0
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            cek.fill(0)
            plaintext.fill(0)
            input.close()
        }
    }

    private const val NONCE_COUNTER_OFFSET = IV_SIZE - Int.SIZE_BYTES
    private const val MAX_CHUNK_SIZE_BYTES = 1024 * 1024
    private val EMPTY_BYTES = ByteArray(0)
}
