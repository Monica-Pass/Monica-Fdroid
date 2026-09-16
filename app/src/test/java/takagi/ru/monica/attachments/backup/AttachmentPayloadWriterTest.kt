package takagi.ru.monica.attachments.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPayloadWriterTest {
    @Test fun streamsAnAttachmentLargerThanTheOldPreviewLimit() = runBlocking {
        val length = 70L * 1024 * 1024 + 13
        var remaining = length
        var largestRead = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Must read in bounded buffers")
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                largestRead = maxOf(largestRead, count)
                if (remaining == 0L) return -1
                val read = minOf(remaining, count.toLong()).toInt()
                buffer.fill(0x5a, offset, offset + read)
                remaining -= read
                return read
            }
        }
        var actual = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { actual++ }
            override fun write(buffer: ByteArray, offset: Int, count: Int) { actual += count }
        }
        val result = writeVerifiedAttachmentPayload(sink, length, null) { copyAttachmentPayload(input, it) }
        assertEquals(length, actual)
        assertEquals(length, result.sizeBytes)
        assertEquals(64, result.sha256Hex.length)
        assertTrue("No attachment-sized allocation", largestRead <= 64 * 1024)
    }

    @Test fun streamedZipPayloadHasTheOriginalBytesAndDigest() = runBlocking {
        val content = ByteArray(300_017) { ((it * 31 + 7) and 0xff).toByte() }
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("attachment.bin"))
            val written = writeVerifiedAttachmentPayload(zip, content.size.toLong(), expectedHash) { output ->
                ByteArrayInputStream(content).use { copyAttachmentPayload(it, output) }
            }
            assertEquals(expectedHash, written.sha256Hex)
            zip.closeEntry()
            // The attachment writer must not close its caller's ZIP stream.
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write("complete".toByteArray())
            zip.closeEntry()
        }
        ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
            assertEquals("attachment.bin", zip.nextEntry.name)
            assertArrayEquals(content, zip.readBytes())
            assertEquals("manifest.txt", zip.nextEntry.name)
            assertEquals("complete", zip.readBytes().decodeToString())
        }
    }

    @Test fun truncatedOrUnexpectedlyLongContentIsNotAcceptedAsACompleteBackup() {
        for (expectedSize in listOf(1L, 3L)) {
            assertThrows(AttachmentPayloadIntegrityException::class.java) {
                runBlocking {
                    writeVerifiedAttachmentPayload(ByteArrayOutputStream(), expectedSize, null) {
                        it.write(byteArrayOf(1, 2))
                    }
                }
            }
        }
    }

    @Test fun mismatchedStoredDigestStopsTheExport() {
        assertThrows(AttachmentPayloadIntegrityException::class.java) {
            runBlocking {
                writeVerifiedAttachmentPayload(ByteArrayOutputStream(), 2, "00".repeat(32)) {
                    it.write(byteArrayOf(1, 2))
                }
            }
        }
    }

    @Test fun keyFailureIsPreservedWithoutWritingAnEmptySuccessfulPayload() {
        val failure = javax.crypto.AEADBadTagException("synthetic bad attachment key")
        val output = ByteArrayOutputStream()
        assertSame(failure, assertThrows(javax.crypto.AEADBadTagException::class.java) {
            runBlocking { writeVerifiedAttachmentPayload(output, 2, null) { throw failure } }
        })
        assertEquals(0, output.size())
    }

    @Test fun cancelledCopyDoesNotReturnSuccessfulMetadata() {
        val cancelled = CancellationException("synthetic cancellation")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            runBlocking { writeVerifiedAttachmentPayload(ByteArrayOutputStream(), 2, null) { throw cancelled } }
        })
    }
}
