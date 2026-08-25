package takagi.ru.monica.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.mdbx.MdbxDiagLogger
import uniffi.mdbx_ffi.createPortableBackup

internal data class Mdbx2ExternalDocument(
    val fileUri: Uri,
    val treeUri: Uri,
    val displayName: String
)

/**
 * Bridges MDBX2's native file requirement with Android document storage.
 *
 * Rust always reads and writes an app-owned working copy. Publication uses a
 * verified portable backup so committed WAL pages are included before the
 * selected SAF document is replaced. External attachment blobs are mirrored
 * into a sibling `<vault-name>.blobs` directory when a persisted tree URI is
 * available.
 */
internal class Mdbx2ExternalStorage(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val lastPublishedFingerprints = ConcurrentHashMap<String, StreamFingerprint>()

    suspend fun createDocument(
        treeUri: Uri,
        requestedName: String,
        workingCopy: File
    ): Mdbx2ExternalDocument = withContext(Dispatchers.IO) {
        val directory = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalArgumentException("Cannot access selected directory")
        check(directory.canWrite()) { "Selected directory is read-only" }

        val displayName = availableDisplayName(directory, requestedName.asMdbxFileName())
        val target = directory.createFile(MDBX_MIME_TYPE, displayName)
            ?: throw IllegalStateException("Failed to create external MDBX2 file")
        try {
            publishMainFile(target.uri, workingCopy)
            mirrorLocalSidecar(
                source = sidecarFor(workingCopy),
                targetDirectory = directory,
                sidecarName = "${target.name ?: displayName}$SIDECAR_SUFFIX"
            )
            lastPublishedFingerprints[target.uri.toString()] = fingerprintUri(target.uri)
        } catch (error: Throwable) {
            runCatching { target.delete() }
            runCatching { directory.findFile("$displayName$SIDECAR_SUFFIX")?.delete() }
            throw error
        }
        Mdbx2ExternalDocument(
            fileUri = target.uri,
            treeUri = treeUri,
            displayName = target.name ?: displayName
        )
    }

    suspend fun publish(database: LocalMdbxDatabase, workingCopy: File) =
        withContext(Dispatchers.IO) {
            require(workingCopy.isFile) {
                "MDBX2 working copy is missing: ${workingCopy.absolutePath}"
            }
            val targetUri = Uri.parse(database.filePath)
            withTargetLease(targetUri) {
                publishLocked(database, targetUri, workingCopy)
                lastPublishedFingerprints[targetUri.toString()] = fingerprintUri(targetUri)
            }
        }

    /**
     * Merge the current external revision into the local working copy before
     * publishing.  The target document remains locked for the complete
     * read/merge/write/verify sequence so two Monica processes cannot both
     * publish a stale snapshot.
     */
    suspend fun publishWithMerge(
        database: LocalMdbxDatabase,
        workingCopy: File,
        mergeRemote: suspend (File) -> Int
    ): Mdbx2ExternalPublishResult = withContext(Dispatchers.IO) {
        require(workingCopy.isFile) {
            "MDBX2 working copy is missing: ${workingCopy.absolutePath}"
        }
        val targetUri = Uri.parse(database.filePath)
        withTargetLease(targetUri) {
            var mergedRemote = false
            var conflictCount = 0
            repeat(MAX_EXTERNAL_MERGE_ATTEMPTS) { attempt ->
                val staged = File(
                    workingCopy.parentFile,
                    ".mdbx2-external-merge-${UUID.randomUUID()}.mdbx"
                )
                try {
                    copyDocumentToOwnedFile(
                        sourceUri = targetUri,
                        targetFile = staged,
                        sourceTreeUri = database.externalTreeUri
                            ?.takeIf(String::isNotBlank)
                            ?.let(Uri::parse)
                    )
                    val stagedFingerprint = staged.inputStream().buffered().use(::fingerprint)
                    val beforeMerge = fingerprintUri(targetUri)
                    if (!beforeMerge.sameAs(stagedFingerprint)) {
                        MdbxDiagLogger.append(
                            "[MDBX2][external-merge] target changed while staging " +
                                "attempt=${attempt + 1}"
                        )
                        return@repeat
                    }

                    val cacheKey = targetUri.toString()
                    val needsMerge = lastPublishedFingerprints[cacheKey]
                        ?.sameAs(stagedFingerprint) != true
                    if (needsMerge) {
                        mergeSidecarIntoWorkingCopy(staged, workingCopy)
                        conflictCount += mergeRemote(staged)
                        mergedRemote = true
                    }

                    val beforePublish = fingerprintUri(targetUri)
                    if (!beforePublish.sameAs(stagedFingerprint)) {
                        MdbxDiagLogger.append(
                            "[MDBX2][external-merge] target changed before publish " +
                                "attempt=${attempt + 1}"
                        )
                        return@repeat
                    }
                    publishLocked(database, targetUri, workingCopy)
                    val published = fingerprintUri(targetUri)
                    lastPublishedFingerprints[cacheKey] = published
                    return@withTargetLease Mdbx2ExternalPublishResult(
                        mergedRemote = mergedRemote,
                        conflictCount = conflictCount
                    )
                } finally {
                    staged.delete()
                    sidecarFor(staged).deleteRecursively()
                }
            }
            throw IOException(
                "External MDBX2 file changed during merge; publication was aborted"
            )
        }
    }

    internal fun mergeSidecarIntoWorkingCopy(sourceFile: File, targetFile: File) {
        val source = sidecarFor(sourceFile)
        if (!source.isDirectory) return
        val target = sidecarFor(targetFile)
        check(target.exists() || target.mkdirs()) {
            "Cannot create MDBX2 local attachment directory"
        }
        mergeLocalDirectory(source, target)
    }

    suspend fun copyDocumentToOwnedFile(
        sourceUri: Uri,
        targetFile: File,
        sourceTreeUri: Uri? = null
    ) = withContext(Dispatchers.IO) {
        check(!targetFile.exists()) { "MDBX2 import target already exists" }
        targetFile.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) {
                "Cannot create MDBX2 working-copy directory"
            }
        }
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                targetFile.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Unable to read selected MDBX2 file")
            check(targetFile.isFile && targetFile.length() > 0L) {
                "Selected MDBX2 file is empty"
            }
            sourceTreeUri?.let { treeUri ->
                val directory = DocumentFile.fromTreeUri(appContext, treeUri)
                    ?: throw IllegalArgumentException("Cannot access selected MDBX2 directory")
                val sourceName = queryDisplayName(sourceUri)
                    ?: throw IllegalArgumentException("Cannot determine selected MDBX2 file name")
                directory.findFile("$sourceName$SIDECAR_SUFFIX")
                    ?.takeIf(DocumentFile::isDirectory)
                    ?.let { sourceSidecar ->
                        copyDocumentDirectoryToFile(sourceSidecar, sidecarFor(targetFile))
                    }
            }
        } catch (error: Throwable) {
            runCatching { targetFile.delete() }
            runCatching { sidecarFor(targetFile).deleteRecursively() }
            throw error
        }
    }

    suspend fun replaceWorkingCopyFromDocument(
        sourceUri: Uri,
        sourceTreeUri: Uri?,
        workingCopy: File,
        validate: (File) -> Unit
    ) = withContext(Dispatchers.IO) {
        val staged = File(
            workingCopy.parentFile,
            ".${workingCopy.nameWithoutExtension}-${UUID.randomUUID()}.mdbx"
        )
        copyDocumentToOwnedFile(sourceUri, staged, sourceTreeUri)
        try {
            validate(staged)
            replaceFile(staged, workingCopy)
            val stagedSidecar = sidecarFor(staged)
            if (stagedSidecar.exists()) {
                val targetSidecar = sidecarFor(workingCopy)
                targetSidecar.deleteRecursively()
                check(stagedSidecar.renameTo(targetSidecar)) {
                    "Unable to activate refreshed MDBX2 attachment directory"
                }
            } else {
                // A source without a sidecar is authoritative.  Do not leave
                // stale local attachment blobs from an older revision attached
                // to the newly refreshed vault.
                sidecarFor(workingCopy).deleteRecursively()
            }
        } finally {
            staged.delete()
            sidecarFor(staged).deleteRecursively()
        }
    }

    suspend fun deleteCreatedDocument(document: Mdbx2ExternalDocument) =
        withContext(Dispatchers.IO) {
            lastPublishedFingerprints.remove(document.fileUri.toString())
            runCatching { DocumentFile.fromSingleUri(appContext, document.fileUri)?.delete() }
            runCatching {
                DocumentFile.fromTreeUri(appContext, document.treeUri)
                    ?.findFile("${document.displayName}$SIDECAR_SUFFIX")
                    ?.delete()
            }
        }

    private fun publishLocked(
        database: LocalMdbxDatabase,
        targetUri: Uri,
        workingCopy: File
    ) {
        publishMainFile(targetUri, workingCopy)
        database.externalTreeUri
            ?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)
            ?.let { treeUri ->
                val directory = DocumentFile.fromTreeUri(appContext, treeUri)
                    ?: throw IllegalStateException("External MDBX2 directory permission is unavailable")
                val targetName = queryDisplayName(targetUri)
                    ?: database.name.asMdbxFileName()
                mirrorLocalSidecar(
                    source = sidecarFor(workingCopy),
                    targetDirectory = directory,
                    sidecarName = "$targetName$SIDECAR_SUFFIX"
                )
            }
    }

    private suspend fun <T> withTargetLease(
        targetUri: Uri,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        for (attempt in 0 until LEASE_ATTEMPTS) {
            val descriptor = runCatching {
                resolver.openFileDescriptor(targetUri, "rw")
            }.getOrElse { error ->
                lastError = error
                null
            }
            if (descriptor != null) {
                val stream = runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                }.getOrElse { error ->
                    descriptor.close()
                    lastError = error
                    null
                }
                if (stream != null) {
                    try {
                        val lock = try {
                            stream.channel.tryLock()
                        } catch (error: OverlappingFileLockException) {
                            lastError = error
                            null
                        } catch (error: IOException) {
                            lastError = error
                            null
                        } catch (error: RuntimeException) {
                            lastError = error
                            null
                        }
                        if (lock != null) {
                            try {
                                return block()
                            } finally {
                                releaseLock(lock)
                            }
                        } else if (lastError == null) {
                            lastError = IOException("External MDBX2 file lease is held")
                        }
                    } finally {
                        runCatching { stream.close() }
                    }
                }
            } else {
                lastError = IOException("Cannot open external MDBX2 file for locking")
            }
            if (attempt + 1 < LEASE_ATTEMPTS) delay(LEASE_RETRY_DELAY_MS)
        }
        throw IllegalStateException(
            "External MDBX2 provider does not support a safe concurrent write lease",
            lastError
        )
    }

    private fun releaseLock(lock: FileLock) {
        runCatching { lock.release() }
    }

    private fun mergeLocalDirectory(source: File, target: File) {
        source.listFiles().orEmpty().forEach { child ->
            val name = child.name.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("MDBX2 sidecar contains an unnamed item")
            val targetChild = File(target, name)
            check(targetChild.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                "MDBX2 sidecar contains an invalid path"
            }
            if (child.isDirectory) {
                check(targetChild.exists() || targetChild.mkdirs()) {
                    "Cannot create MDBX2 local attachment directory"
                }
                check(targetChild.isDirectory) {
                    "MDBX2 sidecar path conflicts with a directory"
                }
                mergeLocalDirectory(child, targetChild)
            } else {
                if (targetChild.exists()) {
                    check(targetChild.isFile) {
                        "MDBX2 sidecar path conflicts with a file"
                    }
                    val sourceFingerprint = child.inputStream().buffered().use(::fingerprint)
                    val targetFingerprint = targetChild.inputStream().buffered().use(::fingerprint)
                    check(sourceFingerprint.sameAs(targetFingerprint)) {
                        "MDBX2 sidecar contains conflicting Blob bytes: $name"
                    }
                } else {
                    val temporary = File(target, ".${name}.${UUID.randomUUID()}.tmp")
                    try {
                        child.inputStream().buffered().use { input ->
                            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                        val sourceFingerprint = child.inputStream().buffered().use(::fingerprint)
                        val temporaryFingerprint = temporary.inputStream().buffered().use(::fingerprint)
                        check(sourceFingerprint.sameAs(temporaryFingerprint)) {
                            "MDBX2 sidecar Blob copy verification failed: $name"
                        }
                        check(temporary.renameTo(targetChild)) {
                            "Cannot activate MDBX2 local attachment Blob: $name"
                        }
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }

    private fun fingerprintUri(uri: Uri): StreamFingerprint =
        resolver.openInputStream(uri)?.buffered()?.use(::fingerprint)
            ?: throw IllegalStateException("Cannot read external MDBX2 file")

    private fun publishMainFile(targetUri: Uri, workingCopy: File) {
        val backupDirectory = File(appContext.cacheDir, "mdbx2-external-publish").also { directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Cannot create MDBX2 publication directory"
            }
        }
        val portableBackup = File(backupDirectory, "${UUID.randomUUID()}.mdbx")
        try {
            createPortableBackup(
                sourcePath = workingCopy.absolutePath,
                destination = portableBackup.absolutePath
            )
            val expected = portableBackup.inputStream().buffered().use(::fingerprint)
            resolver.openOutputStream(targetUri, "rwt")?.use { output ->
                portableBackup.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
            } ?: throw IllegalStateException("Cannot open external MDBX2 file for writing")
            verifyPublishedMainFile(targetUri, expected)
        } finally {
            portableBackup.delete()
        }
    }

    private fun mirrorLocalSidecar(
        source: File,
        targetDirectory: DocumentFile,
        sidecarName: String
    ) {
        val existing = targetDirectory.findFile(sidecarName)
        if (!source.isDirectory || source.listFiles().isNullOrEmpty()) {
            existing?.delete()
            return
        }
        val target = when {
            existing == null -> targetDirectory.createDirectory(sidecarName)
            existing.isDirectory -> existing
            else -> {
                check(existing.delete()) { "Cannot replace external MDBX2 sidecar" }
                targetDirectory.createDirectory(sidecarName)
            }
        } ?: throw IllegalStateException("Cannot create external MDBX2 sidecar")
        mirrorLocalDirectory(source, target)
    }

    private fun mirrorLocalDirectory(source: File, target: DocumentFile) {
        val localChildren = source.listFiles().orEmpty().associateBy(File::getName)
        target.listFiles().forEach { remote ->
            val name = remote.name
            if (name == null || name !in localChildren) {
                check(remote.delete()) { "Cannot remove stale external MDBX2 blob" }
            }
        }
        localChildren.values.forEach { local ->
            if (local.isDirectory) {
                val current = target.findFile(local.name)
                val child = when {
                    current == null -> target.createDirectory(local.name)
                    current.isDirectory -> current
                    else -> {
                        check(current.delete()) { "Cannot replace external MDBX2 blob directory" }
                        target.createDirectory(local.name)
                    }
                } ?: throw IllegalStateException("Cannot create external MDBX2 blob directory")
                mirrorLocalDirectory(local, child)
            } else {
                val current = target.findFile(local.name)
                val child = when {
                    current == null -> target.createFile(BLOB_MIME_TYPE, local.name)
                    current.isFile -> current
                    else -> {
                        check(current.delete()) { "Cannot replace external MDBX2 blob file" }
                        target.createFile(BLOB_MIME_TYPE, local.name)
                    }
                } ?: throw IllegalStateException("Cannot create external MDBX2 blob file")
                copyAndVerify(local, child.uri)
            }
        }
    }

    private fun copyDocumentDirectoryToFile(source: DocumentFile, target: File) {
        check(target.exists() || target.mkdirs()) { "Cannot create MDBX2 sidecar working copy" }
        source.listFiles().forEach { child ->
            val name = child.name?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("External MDBX2 sidecar contains an unnamed item")
            val local = File(target, name)
            check(local.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                "External MDBX2 sidecar contains an invalid path"
            }
            when {
                child.isDirectory -> copyDocumentDirectoryToFile(child, local)
                child.isFile -> resolver.openInputStream(child.uri)?.use { input ->
                    local.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("Cannot read external MDBX2 blob")
            }
        }
    }

    private fun copyAndVerify(source: File, targetUri: Uri) {
        val expectedDigest = source.inputStream().buffered().use(::sha256)
        resolver.openOutputStream(targetUri, "rwt")?.use { output ->
            source.inputStream().buffered().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot write external MDBX2 blob")
        val actualDigest = resolver.openInputStream(targetUri)?.buffered()?.use(::sha256)
            ?: throw IllegalStateException("Cannot verify external MDBX2 blob")
        check(MessageDigest.isEqual(expectedDigest, actualDigest)) {
            "External MDBX2 blob digest verification failed"
        }
    }

    private fun verifyPublishedMainFile(targetUri: Uri, expected: StreamFingerprint) {
        var actual: StreamFingerprint? = null
        var readFailure: Throwable? = null
        for (attempt in 0 until PUBLISH_VERIFY_ATTEMPTS) {
            val result = runCatching {
                resolver.openInputStream(targetUri)?.buffered()?.use(::fingerprint)
                    ?: throw IllegalStateException("Cannot verify external MDBX2 file")
            }
            actual = result.getOrNull()
            readFailure = result.exceptionOrNull()
            val verified = actual?.let { value ->
                value.length == expected.length &&
                    MessageDigest.isEqual(expected.digest, value.digest)
            } == true
            if (verified) return

            MdbxDiagLogger.append(
                "[MDBX2][external-publish] verifyAttempt=${attempt + 1} " +
                    "expectedBytes=${expected.length} actualBytes=${actual?.length ?: -1L} " +
                    "readable=${readFailure == null}"
            )
            if (attempt < PUBLISH_VERIFY_DELAYS_MS.size) {
                try {
                    Thread.sleep(PUBLISH_VERIFY_DELAYS_MS[attempt])
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        readFailure?.let { error ->
            throw IllegalStateException("Cannot verify external MDBX2 file", error)
        }
        val finalActual = checkNotNull(actual) { "Cannot verify external MDBX2 file" }
        check(finalActual.length == expected.length) {
            "External MDBX2 file length verification failed: " +
                "expected=${expected.length}, actual=${finalActual.length}"
        }
        check(MessageDigest.isEqual(expected.digest, finalActual.digest)) {
            "External MDBX2 file digest verification failed"
        }
    }

    private fun replaceFile(staged: File, target: File) {
        listOf(File("${target.absolutePath}-wal"), File("${target.absolutePath}-shm")).forEach(File::delete)
        val backup = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.bak")
        if (target.exists()) {
            check(target.renameTo(backup)) { "Unable to stage current MDBX2 working copy" }
        }
        try {
            check(staged.renameTo(target)) { "Unable to activate refreshed MDBX2 working copy" }
            backup.delete()
        } catch (error: Throwable) {
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun availableDisplayName(directory: DocumentFile, requestedName: String): String {
        if (directory.findFile(requestedName) == null) return requestedName
        val base = requestedName.removeSuffix(".mdbx")
        var index = 2
        while (true) {
            val candidate = "$base ($index).mdbx"
            if (directory.findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }

    private fun fingerprint(input: InputStream): StreamFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var length = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            length += count
        }
        return StreamFingerprint(length = length, digest = digest.digest())
    }

    private fun sha256(input: InputStream): ByteArray = fingerprint(input).digest

    private fun sidecarFor(file: File): File = File("${file.absolutePath}$SIDECAR_SUFFIX")

    private fun String.asMdbxFileName(): String =
        trim().ifBlank { "Monica" }.let { value ->
            if (value.endsWith(".mdbx", ignoreCase = true)) value else "$value.mdbx"
        }

    companion object {
        private const val MDBX_MIME_TYPE = "application/octet-stream"
        private const val BLOB_MIME_TYPE = "application/octet-stream"
        private const val SIDECAR_SUFFIX = ".blobs"
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val PUBLISH_VERIFY_ATTEMPTS = 3
        private val PUBLISH_VERIFY_DELAYS_MS = longArrayOf(40L, 120L)
        private const val MAX_EXTERNAL_MERGE_ATTEMPTS = 3
        private const val LEASE_ATTEMPTS = 300
        private const val LEASE_RETRY_DELAY_MS = 100L
    }
}

internal data class Mdbx2ExternalPublishResult(
    val mergedRemote: Boolean,
    val conflictCount: Int
)

private data class StreamFingerprint(
    val length: Long,
    val digest: ByteArray
) {
    fun sameAs(other: StreamFingerprint): Boolean =
        length == other.length && MessageDigest.isEqual(digest, other.digest)
}
