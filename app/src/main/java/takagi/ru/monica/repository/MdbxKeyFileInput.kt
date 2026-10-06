package takagi.ru.monica.repository

import java.io.InputStream

/** Same 1 MiB credential limit at selection and reopen; no growable secret-buffer copies. */
internal fun InputStream.readMdbxKeyFileBytes(expectedFingerprint: String? = null): ByteArray {
    val limit = 1024 * 1024
    val scratch = ByteArray(limit + 1)
    var result: ByteArray? = null
    try {
        var total = 0
        while (total < scratch.size) {
            val count = read(scratch, total, scratch.size - total)
            if (count < 0) break
            if (count == 0) {
                val next = read()
                if (next < 0) break
                scratch[total++] = next.toByte()
            } else total += count
        }
        require(total <= limit) { "MDBX key file is too large" }
        val bytes = scratch.copyOf(total).also { result = it }
        expectedFingerprint?.takeIf { it.isNotBlank() }?.let { expected ->
            check(MdbxVaultCrypto.fingerprint(bytes).equals(expected, ignoreCase = true)) {
                "MDBX key file fingerprint does not match"
            }
        }
        return bytes.also { result = null }
    } finally {
        scratch.fill(0)
        result?.fill(0)
    }
}
