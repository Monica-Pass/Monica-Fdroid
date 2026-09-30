package takagi.ru.monica.data

import java.io.InputStream
import java.security.MessageDigest

/** Actual native object attachments; these identities never enter the Room owner facade. */
data class NativeApiTokenAttachment(
    val id: String, val fileName: String, val mimeType: String, val size: Long, val sha256: String,
) {
    override fun toString() = "NativeApiTokenAttachment(redacted)"
}

/** A reopenable, caller-owned source. Publishing never consumes or deletes the source. */
class NativeApiTokenUpload(
    val fileName: String,
    val mimeType: String,
    val expectedSize: Long? = null,
    val expectedSha256: String? = null,
    val open: suspend () -> InputStream,
)

object NativeApiTokenAssets {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_COUNT = 128

    /** SAF chooses the destination; only pass a basename suggestion to its document picker. */
    fun exportName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.trim().trim('.').take(200).ifBlank { "attachment" }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun validate(bytes: ByteArray, size: Long?, hash: String?) {
        require(bytes.size.toLong() <= MAX_BYTES) { "Native attachment exceeds 64 MiB" }
        require(size == null || size == bytes.size.toLong()) { "Native attachment size changed" }
        require(hash.isNullOrBlank() || hash.equals(digest(bytes), ignoreCase = true)) { "Native attachment content changed" }
    }

    fun readBounded(input: InputStream, maxBytes: Long = MAX_BYTES): ByteArray {
        require(maxBytes in 0..MAX_BYTES)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maxBytes) { "Native attachments exceed 64 MiB per save" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally { buffer.fill(0) }
    }
}
