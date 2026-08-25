package takagi.ru.monica.utils

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Path-oriented transport boundary used by the MDBX2 incremental coordinator.
 *
 * The existing [MdbxFileSource] API is intentionally kept for the legacy
 * complete-file path. MDBX2 needs immutable objects, conditional creation and
 * file-to-file transfers so a failed request never advances engine state.
 */
interface MdbxRemoteTransport {
    suspend fun testConnection()

    suspend fun stat(path: String): MdbxRemoteObject?

    suspend fun list(path: String? = null): List<MdbxRemoteObject>

    suspend fun ensureDirectory(path: String)

    suspend fun readTo(path: String, destination: File)

    suspend fun writeFrom(
        path: String,
        source: File,
        mode: MdbxRemoteWriteMode = MdbxRemoteWriteMode.CREATE_ONLY,
        expectedVersion: String? = null
    ): MdbxRemoteObject
}

enum class MdbxRemoteWriteMode {
    /** Create an immutable object; an existing identical object is idempotent. */
    CREATE_ONLY,

    /** Replace an existing object, optionally guarded by its version token. */
    REPLACE,

    /** Replace only when the provider version token still matches. */
    IF_MATCH
}

data class MdbxRemoteObject(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val versionToken: String? = null,
    val lastModified: Long? = null
)

/** Stable, provider-independent names for the MDBX2 remote object tree. */
object MdbxRemoteSyncPaths {
    private const val SYNC_SUFFIX = ".sync"
    private const val SEGMENT_SUFFIX = ".mdbxsync"

    fun syncRoot(remoteVaultPath: String): String = normalizePath(remoteVaultPath) + SYNC_SUFFIX

    fun streamsRoot(remoteVaultPath: String): String = "${syncRoot(remoteVaultPath)}/streams"

    fun blobsRoot(remoteVaultPath: String): String = "${syncRoot(remoteVaultPath)}/blobs"

    fun streamRoot(remoteVaultPath: String, deviceId: String, generationId: String): String =
        "${streamsRoot(remoteVaultPath)}/${component(deviceId)}/${component(generationId)}"

    fun segmentPath(
        remoteVaultPath: String,
        deviceId: String,
        generationId: String,
        sequence: UInt,
        digestHex: String
    ): String {
        val digest = digestHex.lowercase(Locale.ROOT)
        require(digest.length == 64 && digest.all { it in "0123456789abcdef" }) {
            "MDBX2 segment digest must be a SHA-256 hex value"
        }
        return "${streamRoot(remoteVaultPath, deviceId, generationId)}/" +
            "segments/${sequence.toString().padStart(10, '0')}-$digest$SEGMENT_SUFFIX"
    }

    fun blobPath(remoteVaultPath: String, blobId: String): String {
        val id = blobId.lowercase(Locale.ROOT)
        require(id.length == 64 && id.all { it in "0123456789abcdef" }) {
            "MDBX2 Blob ID must be a SHA-256 hex value"
        }
        return "${blobsRoot(remoteVaultPath)}/${id.substring(0, 2)}/${id.substring(2, 4)}/$id"
    }

    fun normalizePath(path: String): String {
        val normalized = path.trim().replace('\\', '/').trim('/')
            .replace(Regex("/+"), "/")
        require(normalized.isNotBlank()) { "MDBX2 remote path cannot be blank" }
        require(normalized.split('/').none { it == "." || it == ".." || it.isBlank() }) {
            "MDBX2 remote path contains an unsafe component"
        }
        return normalized
    }

    fun component(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotBlank() && normalized.length <= 256) {
            "MDBX2 remote identifier is blank or too long"
        }
        require(normalized != "." && normalized != ".." &&
            '/' !in normalized && '\\' !in normalized && '\u0000' !in normalized) {
            "MDBX2 remote identifier contains an unsafe character"
        }
        return normalized
    }

    fun sha256Hex(file: File): String {
        require(file.isFile) { "Cannot hash missing remote transfer file" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun requireRegularFile(file: File) {
        if (!file.isFile || !file.canRead()) {
            throw IOException("MDBX2 transfer source is not a readable regular file")
        }
    }
}
