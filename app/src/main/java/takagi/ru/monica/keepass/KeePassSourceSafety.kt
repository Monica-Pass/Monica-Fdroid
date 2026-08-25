package takagi.ru.monica.keepass

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant

internal data class KeePassSourceRevision(
    val sha256: String,
    val sizeBytes: Long
)

internal class KeePassSourceChangedException(message: String) : IOException(message)

internal object KeePassSourceSafety {
    fun revisionOf(bytes: ByteArray): KeePassSourceRevision {
        return KeePassSourceRevision(
            sha256 = sha256(bytes),
            sizeBytes = bytes.size.toLong()
        )
    }

    /** Computes a source revision without materialising the complete file. */
    fun revisionOf(input: InputStream): KeePassSourceRevision {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        DigestInputStream(input, digest).use { digestInput ->
            while (true) {
                val read = digestInput.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                size += read
            }
        }
        return KeePassSourceRevision(
            sha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
            sizeBytes = size
        )
    }

    fun revisionOf(file: File): KeePassSourceRevision {
        if (!file.isFile) throw IOException("Source file does not exist: ${file.path}")
        return file.inputStream().use { revisionOf(it) }
    }

    /** Copies a stream while calculating its revision without retaining the payload in memory. */
    fun copyAndRevision(input: InputStream, output: java.io.OutputStream): KeePassSourceRevision {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            size += read
        }
        return KeePassSourceRevision(
            sha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
            sizeBytes = size,
        )
    }

    fun requireUnchanged(
        expectedRevision: KeePassSourceRevision,
        currentBytes: ByteArray,
        sourceLabel: String
    ) {
        requireUnchanged(
            expectedRevision = expectedRevision,
            currentRevision = revisionOf(currentBytes),
            sourceLabel = sourceLabel
        )
    }

    fun requireUnchanged(
        expectedRevision: KeePassSourceRevision,
        currentRevision: KeePassSourceRevision,
        sourceLabel: String
    ) {
        if (currentRevision != expectedRevision) {
            throw KeePassSourceChangedException(
                "数据库文件已被其他应用修改，请重新加载后再保存：$sourceLabel"
            )
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

internal object KeePassRemoteVersionPolicy {
    fun preferred(versionToken: String?, etag: String?): String? {
        return versionToken?.takeIf(String::isNotBlank)
            ?: etag?.takeIf(String::isNotBlank)
    }

    fun matches(expected: String?, versionToken: String?, etag: String?): Boolean {
        val required = expected?.takeIf(String::isNotBlank) ?: return true
        return sequenceOf(versionToken, etag)
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .any { actual -> actual == required }
    }
}

internal data class KeePassRecoveryCopy(
    val file: File,
    val revision: KeePassSourceRevision,
    val databaseId: Long = file.parentFile?.name?.toLongOrNull() ?: 0L,
    val createdAt: Instant = Instant.ofEpochMilli(file.lastModified().coerceAtLeast(0L))
)

internal data class KeePassRecoveryRecord(
    val databaseId: Long,
    val file: File,
    val createdAt: Instant,
    val revision: KeePassSourceRevision,
    val verified: Boolean,
    val expectedHashPrefix: String
)

internal class KeePassRecoveryStore(
    private val rootDir: File,
    private val nowProvider: () -> Instant = Instant::now
) {
    fun create(
        databaseId: Long,
        bytes: ByteArray,
        revision: KeePassSourceRevision = KeePassSourceSafety.revisionOf(bytes)
    ): KeePassRecoveryCopy {
        require(databaseId > 0) { "Recovery copy requires a database id" }
        val databaseDir = File(rootDir, databaseId.toString())
        if (!databaseDir.exists() && !databaseDir.mkdirs()) {
            throw IOException("无法创建 KeePass 恢复目录")
        }

        val timestamp = nowProvider().toEpochMilli()
        val fileName = "recovery-$timestamp-${revision.sha256.take(12)}.kdbx"
        val destination = File(databaseDir, fileName)
        val temporary = File(databaseDir, "$fileName.tmp")
        writeSynced(temporary, bytes)
        if (!temporary.renameTo(destination)) {
            writeSynced(destination, bytes)
            if (!temporary.delete() && temporary.exists()) {
                throw IOException("无法清理 KeePass 恢复临时文件")
            }
        }

        destination.setLastModified(timestamp)
        val copy = KeePassRecoveryCopy(
            file = destination,
            revision = revision,
            databaseId = databaseId,
            createdAt = Instant.ofEpochMilli(timestamp)
        )
        if (!verify(copy)) {
            throw IOException("KeePass 恢复副本校验失败")
        }
        return copy
    }

    fun create(databaseId: Long, input: InputStream): KeePassRecoveryCopy {
        require(databaseId > 0) { "Recovery copy requires a database id" }
        val databaseDir = File(rootDir, databaseId.toString())
        if (!databaseDir.exists() && !databaseDir.mkdirs()) {
            throw IOException("无法创建 KeePass 恢复目录")
        }
        val timestamp = nowProvider().toEpochMilli()
        val staging = File(databaseDir, ".recovery-$timestamp-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            FileOutputStream(staging).use { output ->
                input.use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        size += read
                    }
                }
                output.flush()
                output.fd.sync()
            }
            val revision = KeePassSourceRevision(
                sha256 = digest.digest().joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                },
                sizeBytes = size,
            )
            val destination = File(
                databaseDir,
                "recovery-$timestamp-${revision.sha256.take(12)}.kdbx",
            )
            if (!staging.renameTo(destination)) {
                staging.inputStream().use { source ->
                    FileOutputStream(destination).use { output ->
                        source.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                }
                staging.delete()
            }
            destination.setLastModified(timestamp)
            return KeePassRecoveryCopy(
                file = destination,
                revision = revision,
                databaseId = databaseId,
                createdAt = Instant.ofEpochMilli(timestamp),
            ).also { copy ->
                if (!verify(copy)) throw IOException("KeePass 恢复副本校验失败")
            }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun verify(copy: KeePassRecoveryCopy): Boolean {
        if (!copy.file.isFile) return false
        return runCatching {
            KeePassSourceSafety.revisionOf(copy.file) == copy.revision
        }.getOrDefault(false)
    }

    fun deleteVerified(copy: KeePassRecoveryCopy): Boolean {
        if (!verify(copy)) return false
        return !copy.file.exists() || copy.file.delete()
    }

    fun list(databaseId: Long): List<KeePassRecoveryRecord> {
        if (databaseId <= 0) return emptyList()
        val databaseDir = databaseDirectory(databaseId)
        return databaseDir.listFiles { file -> file.isFile && RECOVERY_FILE.matches(file.name) }
            .orEmpty()
            .mapNotNull { file ->
                val match = RECOVERY_FILE.matchEntire(file.name) ?: return@mapNotNull null
                val timestamp = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val expectedHashPrefix = match.groupValues[2]
                val revision = runCatching { KeePassSourceSafety.revisionOf(file) }.getOrNull()
                    ?: KeePassSourceRevision(sha256 = "", sizeBytes = file.length())
                KeePassRecoveryRecord(
                    databaseId = databaseId,
                    file = file,
                    createdAt = Instant.ofEpochMilli(timestamp),
                    revision = revision,
                    verified = revision.sha256.startsWith(expectedHashPrefix, ignoreCase = true),
                    expectedHashPrefix = expectedHashPrefix
                )
            }
            .sortedWith(compareByDescending<KeePassRecoveryRecord> { it.createdAt }.thenByDescending { it.file.name })
    }

    fun export(record: KeePassRecoveryRecord, destination: File): KeePassSourceRevision {
        return copyVerifiedRecord(record, destination)
    }

    fun restore(record: KeePassRecoveryRecord, destination: File): KeePassSourceRevision {
        return copyVerifiedRecord(record, destination)
    }

    fun delete(record: KeePassRecoveryRecord): Boolean {
        requireRecordInsideStore(record)
        return !record.file.exists() || record.file.delete()
    }

    fun prune(databaseId: Long, keepNewest: Int = DEFAULT_RETAINED_COPIES) {
        if (databaseId <= 0 || keepNewest < 0) return
        val databaseDir = databaseDirectory(databaseId)
        val copies = databaseDir.listFiles { file ->
            file.isFile && file.name.startsWith("recovery-") && file.name.endsWith(".kdbx")
        }?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }).orEmpty()
        copies.drop(keepNewest).forEach { stale ->
            runCatching { stale.delete() }
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun copyVerifiedRecord(
        record: KeePassRecoveryRecord,
        destination: File
    ): KeePassSourceRevision {
        requireRecordInsideStore(record)
        val currentRevision = KeePassSourceSafety.revisionOf(record.file)
        if (!currentRevision.sha256.startsWith(record.expectedHashPrefix, ignoreCase = true)) {
            throw IOException("KeePass recovery copy verification failed")
        }
        val writtenRevision = record.file.inputStream().use { input ->
            KeePassRawFileOperations.saveCopy(input, destination)
        }
        if (writtenRevision != currentRevision) {
            throw IOException("KeePass recovery destination verification failed")
        }
        return writtenRevision
    }

    private fun requireRecordInsideStore(record: KeePassRecoveryRecord) {
        require(record.databaseId > 0) { "Recovery record requires a database id" }
        val expectedDirectory = databaseDirectory(record.databaseId).canonicalFile
        val parent = record.file.canonicalFile.parentFile
        require(parent == expectedDirectory) { "Recovery record is outside the recovery store" }
    }

    private fun databaseDirectory(databaseId: Long): File = File(rootDir, databaseId.toString())

    private companion object {
        const val DEFAULT_RETAINED_COPIES = 3
        val RECOVERY_FILE = Regex("recovery-(\\d+)-([0-9a-fA-F]{12})\\.kdbx")
    }
}

internal object KeePassRawFileOperations {
    fun saveCopy(bytes: ByteArray, destination: File): KeePassSourceRevision {
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("KeePass destination has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create KeePass destination directory")
        }
        val temporary = File(parent, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                FileOutputStream(destination).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
            }
            val revision = KeePassSourceSafety.revisionOf(destination.readBytes())
            val expected = KeePassSourceSafety.revisionOf(bytes)
            if (revision != expected) {
                throw IOException("KeePass save-copy verification failed")
            }
            return revision
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Stream variant used by recovery/export paths for large databases. */
    fun saveCopy(input: InputStream, destination: File): KeePassSourceRevision {
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("KeePass destination has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create KeePass destination directory")
        }
        val temporary = File(parent, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            val expected = FileOutputStream(temporary).use { output ->
                val revision = KeePassSourceSafety.copyAndRevision(input, output)
                output.flush()
                output.fd.sync()
                revision
            }
            if (!temporary.renameTo(destination)) {
                temporary.inputStream().use { source ->
                    FileOutputStream(destination).use { output ->
                        source.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
            val actual = KeePassSourceSafety.revisionOf(destination)
            if (actual != expected) {
                throw IOException("KeePass save-copy verification failed")
            }
            return actual
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
