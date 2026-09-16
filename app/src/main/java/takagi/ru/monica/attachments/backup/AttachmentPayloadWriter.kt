package takagi.ru.monica.attachments.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class WrittenAttachmentPayload(val sizeBytes: Long, val sha256Hex: String)

internal class AttachmentPayloadIntegrityException : GeneralSecurityException("Attachment payload integrity check failed")

/** Copy without retaining the complete plaintext or applying a preview's in-memory size limit. */
internal suspend fun copyAttachmentPayload(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
    } finally {
        buffer.fill(0)
    }
}

/** The caller owns [output]; a failed attachment must invalidate the entire archive. */
internal suspend fun writeVerifiedAttachmentPayload(
    output: OutputStream,
    expectedSize: Long,
    expectedSha256: String?,
    write: suspend (OutputStream) -> Unit,
): WrittenAttachmentPayload {
    val digest = MessageDigest.getInstance("SHA-256")
    val job = currentCoroutineContext()
    var written = 0L
    val checked = object : OutputStream() {
        private fun count(length: Int) {
            job.ensureActive()
            if (length < 0 || written > Long.MAX_VALUE - length) throw AttachmentPayloadIntegrityException()
            written += length
            if (expectedSize > 0 && written > expectedSize) throw AttachmentPayloadIntegrityException()
        }

        override fun write(value: Int) {
            count(1)
            output.write(value)
            digest.update(value.toByte())
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            count(length)
            output.write(buffer, offset, length)
            digest.update(buffer, offset, length)
        }
    }
    write(checked)
    job.ensureActive()
    if (expectedSize > 0 && written != expectedSize) throw AttachmentPayloadIntegrityException()
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    if (!expectedSha256.isNullOrBlank() && !sha256.equals(expectedSha256, ignoreCase = true)) {
        throw AttachmentPayloadIntegrityException()
    }
    return WrittenAttachmentPayload(written, sha256)
}
