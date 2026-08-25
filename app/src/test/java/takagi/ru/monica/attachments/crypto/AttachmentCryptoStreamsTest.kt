package takagi.ru.monica.attachments.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCryptoStreamsTest {

    @Test
    fun chunkedFormat_roundTripsEmptyAttachment() {
        assertArrayEquals(ByteArray(0), decryptChunked(encryptChunked(ByteArray(0))))
    }

    @Test
    fun chunkedFormat_roundTripsAcrossChunkBoundaries() {
        val plaintext = deterministicBytes(AttachmentCryptoStreams.CHUNK_SIZE_BYTES * 2 + 37)

        assertArrayEquals(plaintext, decryptChunked(encryptChunked(plaintext)))
    }

    @Test
    fun chunkedFormat_rejectsTamperedChunk() {
        val encrypted = encryptChunked(deterministicBytes(1_024))
        encrypted[CHUNKED_HEADER_BYTES + Int.SIZE_BYTES + 17] =
            (encrypted[CHUNKED_HEADER_BYTES + Int.SIZE_BYTES + 17].toInt() xor 0x01).toByte()

        assertIOException { decryptChunked(encrypted) }
    }

    @Test
    fun chunkedFormat_rejectsTruncatedEndTag() {
        val encrypted = encryptChunked(deterministicBytes(1_024))

        assertIOException { decryptChunked(encrypted.copyOf(encrypted.size - 1)) }
    }

    @Test
    fun chunkedFormat_rejectsTrailingData() {
        val encrypted = encryptChunked(deterministicBytes(1_024)) + byteArrayOf(0x55)

        assertIOException { decryptChunked(encrypted) }
    }

    @Test
    fun legacySingleSegmentFormat_remainsDecryptable() {
        val plaintext = deterministicBytes(4_097)
        val iv = AttachmentCryptoStreams.newIv()
        val out = ByteArrayOutputStream()
        out.write(iv)
        AttachmentCryptoStreams.encryptingStream(out, CEK, iv).use { encrypted ->
            encrypted.write(plaintext)
        }
        val input = ByteArrayInputStream(out.toByteArray())
        val storedIv = ByteArray(AttachmentCryptoStreams.IV_SIZE)
        assertTrue(input.read(storedIv) == storedIv.size)

        val decrypted = AttachmentCryptoStreams.decryptingStream(input, CEK, storedIv).use {
            it.readBytes()
        }

        assertArrayEquals(plaintext, decrypted)
    }

    private fun encryptChunked(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        AttachmentCryptoStreams.writeChunkedEncrypted(
            source = ByteArrayInputStream(plaintext),
            out = out,
            cek = CEK
        )
        return out.toByteArray()
    }

    private fun decryptChunked(encrypted: ByteArray): ByteArray {
        val input = ByteArrayInputStream(encrypted)
        val magic = ByteArray(AttachmentCryptoStreams.chunkedMagicSize())
        check(input.read(magic) == magic.size)
        check(AttachmentCryptoStreams.isChunkedMagic(magic))
        return AttachmentCryptoStreams.chunkedDecryptingStream(input, CEK).use { it.readBytes() }
    }

    private fun deterministicBytes(size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + 7) and 0xff).toByte() }

    private fun assertIOException(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException)
    }

    private companion object {
        val CEK = ByteArray(AttachmentCryptoStreams.CEK_SIZE_BYTES) { index -> (index + 1).toByte() }
        const val CHUNKED_HEADER_BYTES = 8 + Int.SIZE_BYTES + AttachmentCryptoStreams.IV_SIZE
    }
}
