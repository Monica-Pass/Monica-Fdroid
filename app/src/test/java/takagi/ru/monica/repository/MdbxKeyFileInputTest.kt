package takagi.ru.monica.repository

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class MdbxKeyFileInputTest {
    @Test fun readsShortChunksWithoutChangingKeyBytesAndWipesScratch() {
        val expected = ByteArray(87) { it.toByte() }
        val source = ObservedInput(expected, chunkSize = 3)
        assertArrayEquals(expected, source.readMdbxKeyFileBytes())
        assertTrue(source.scratch!!.all { it == 0.toByte() })
    }

    @Test fun acceptsExactExistingOneMiBLimit() {
        val expected = ByteArray(1024 * 1024) { (it % 251).toByte() }
        assertArrayEquals(expected, ByteArrayInputStream(expected).readMdbxKeyFileBytes())
    }

    @Test fun oversizedInputIsRejectedBeforeReadingTheRemainderAndWipesScratch() {
        val source = ObservedInput(ByteArray(2 * 1024 * 1024) { 7 })
        assertTrue(runCatching { source.readMdbxKeyFileBytes() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1024 * 1024 + 1, source.offset)
        assertTrue(source.scratch!!.all { it == 0.toByte() })
    }

    @Test fun ioFailureAfterPartialReadWipesScratch() {
        val source = ObservedInput(ByteArray(64) { 9 }, chunkSize = 8, failAt = 8)
        assertTrue(runCatching { source.readMdbxKeyFileBytes() }.exceptionOrNull() is IOException)
        assertTrue(source.scratch!!.all { it == 0.toByte() })
    }

    @Test fun zeroLengthReadMakesProgressAndEmptyFileRemainsForCredentialValidation() {
        val source = object : ByteArrayInputStream(byteArrayOf(3, 4, 5)) {
            var zeroFirst = true
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                if (zeroFirst) { zeroFirst = false; 0 } else super.read(target, offset, length)
        }
        assertArrayEquals(byteArrayOf(3, 4, 5), source.readMdbxKeyFileBytes())
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readMdbxKeyFileBytes())
    }

    @Test fun fingerprintMismatchIsRejectedAndMatchingFingerprintPreservesBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        assertTrue(runCatching { ByteArrayInputStream(bytes).readMdbxKeyFileBytes("wrong") }.isFailure)
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readMdbxKeyFileBytes(MdbxVaultCrypto.fingerprint(bytes)))
    }

    private class ObservedInput(
        val bytes: ByteArray,
        val chunkSize: Int = Int.MAX_VALUE,
        val failAt: Int = Int.MAX_VALUE,
    ) : InputStream() {
        var offset = 0
        var scratch: ByteArray? = null
        override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 255
        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            scratch = target
            if (offset >= failAt) throw IOException("synthetic interrupted input")
            if (offset == bytes.size) return -1
            val count = minOf(length, chunkSize, bytes.size - offset)
            bytes.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            return count
        }
    }
}
