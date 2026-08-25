package takagi.ru.monica.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MdbxSyncSidecarSegment(
    val streamId: String,
    val generationId: String,
    val sequence: Long,
    val fileName: String,
    val digestHex: String,
    val sizeBytes: Long,
    val uploaded: Boolean = false,
    val applied: Boolean = false
)

@Serializable
data class MdbxSyncSidecarBlob(
    val blobId: String,
    val totalSize: Long,
    val uploaded: Boolean = false,
    val downloaded: Boolean = false
)

@Serializable
data class MdbxSyncSidecarManifest(
    val format: String = FORMAT,
    val vaultId: String,
    val generationId: String,
    val streamId: String,
    val remoteVaultPath: String? = null,
    val nextSequence: Long = 0,
    val segments: List<MdbxSyncSidecarSegment> = emptyList(),
    val blobs: List<MdbxSyncSidecarBlob> = emptyList()
) {
    fun validate(): MdbxSyncSidecarManifest {
        require(format == FORMAT) { "Unsupported MDBX2 sidecar format: $format" }
        require(vaultId.isNotBlank() && vaultId.length <= MAX_IDENTIFIER_BYTES)
        require(generationId.isNotBlank() && generationId.length <= MAX_IDENTIFIER_BYTES)
        require(streamId.isNotBlank() && streamId.length <= MAX_IDENTIFIER_BYTES)
        remoteVaultPath?.let { path ->
            require(path.length <= MAX_REMOTE_PATH_LENGTH)
            require(MdbxRemoteSyncPaths.normalizePath(path) == path)
        }
        require(nextSequence >= 0)
        require(segments.size <= MAX_SEGMENTS)
        require(blobs.size <= MAX_BLOBS)
        require(segments.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        require(blobs.zipWithNext().all { (a, b) -> a.blobId < b.blobId })
        return this
    }

    companion object {
        const val FORMAT = "mdbx2-sync-sidecar-v1"
        const val MAX_IDENTIFIER_BYTES = 512
        const val MAX_REMOTE_PATH_LENGTH = 4096
        const val MAX_SEGMENTS = 100_000
        const val MAX_BLOBS = 250_000
    }
}

/**
 * Atomic, bounded sidecar manifest storage. The manifest is deliberately
 * separate from the encrypted vault so a failed network operation cannot
 * mutate engine state or leave a half-written cursor.
 */
class MdbxSyncSidecarStore(
    private val rootDirectory: File,
    private val json: Json = DEFAULT_JSON
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun read(file: File): MdbxSyncSidecarManifest? = withFileLock(file) {
        requireOwnedPath(file)
        if (!file.isFile) return@withFileLock null
        if (file.length() > MAX_FILE_BYTES) {
            throw IOException("MDBX2 sidecar manifest exceeds $MAX_FILE_BYTES bytes")
        }
        val manifest = json.decodeFromString<MdbxSyncSidecarManifest>(file.readText())
        manifest.validate()
    }

    suspend fun write(file: File, manifest: MdbxSyncSidecarManifest) = withFileLock(file) {
        requireOwnedPath(file)
        val encoded = json.encodeToString(manifest.validate())
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "MDBX2 sidecar manifest exceeds $MAX_FILE_BYTES bytes"
        }
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Cannot create MDBX2 sidecar directory"
        }
        val temporary = File.createTempFile(".mdbx-sync-", ".tmp", rootDirectory)
        try {
            temporary.outputStream().use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (!temporary.renameTo(file)) {
                    throw IOException("Cannot publish MDBX2 sidecar manifest")
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    suspend fun update(
        file: File,
        transform: (MdbxSyncSidecarManifest?) -> MdbxSyncSidecarManifest
    ): MdbxSyncSidecarManifest = withFileLock(file) {
        val current = if (file.isFile) {
            if (file.length() > MAX_FILE_BYTES) {
                throw IOException("MDBX2 sidecar manifest exceeds $MAX_FILE_BYTES bytes")
            }
            json.decodeFromString<MdbxSyncSidecarManifest>(file.readText()).validate()
        } else {
            null
        }
        val next = transform(current).validate()
        // Reuse the locked primitive without taking the same Mutex recursively.
        val encoded = json.encodeToString(next)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES)
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Cannot create MDBX2 sidecar directory"
        }
        val temporary = File.createTempFile(".mdbx-sync-", ".tmp", rootDirectory)
        try {
            temporary.outputStream().use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (!temporary.renameTo(file)) {
                    throw IOException("Cannot publish MDBX2 sidecar manifest")
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        next
    }

    private suspend fun <T> withFileLock(file: File, block: suspend () -> T): T {
        requireOwnedPath(file)
        val key = file.canonicalPath
        return locks.getOrPut(key) { Mutex() }.withLock { block() }
    }

    private fun requireOwnedPath(file: File) {
        val root = rootDirectory.canonicalFile
        val candidate = file.canonicalFile
        require(candidate.parentFile == root) {
            "MDBX2 sidecar path must be directly below the app-private directory"
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024L
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
