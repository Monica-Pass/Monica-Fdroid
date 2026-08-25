package takagi.ru.monica.utils

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OneDriveMdbxFileSource(
    private val context: Context,
    private val accountId: String
) : MdbxFileSource {

    private fun delegate(remotePath: String? = null) =
        OneDriveKeePassFileSource(context, accountId, remotePath = remotePath)

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { delegate().testConnection().getOrThrow() }
    }

    override suspend fun listDirectory(path: String?): List<FileSourceEntry> =
        withContext(Dispatchers.IO) {
            val normalizedPath = OneDriveKeePassFileSource.normalizeOptionalRemotePath(path)
            listDirectoryAll(normalizedPath)
                .filter { it.isDirectory || it.name.endsWith(".mdbx", ignoreCase = true) }
                .sortedWith(
                    compareByDescending<FileSourceEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
        }

    suspend fun listDirectoryAll(path: String?): List<FileSourceEntry> =
        withContext(Dispatchers.IO) {
            val normalizedPath = OneDriveKeePassFileSource.normalizeOptionalRemotePath(path)
            delegate().listDirectory(normalizedPath)
        }

    override suspend fun createDirectory(
        parentPath: String?,
        name: String
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        delegate().createDirectory(parentPath, name)
    }

    override suspend fun createPlaceholderFile(
        parentPath: String?,
        name: String
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        delegate().createFileInDirectory(parentPath, name, ByteArray(0))
    }

    override suspend fun writeFile(
        parentPath: String?,
        name: String,
        bytes: ByteArray
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = OneDriveKeePassFileSource.normalizeOptionalRemotePath(parentPath)
        val targetPath = OneDriveKeePassFileSource.buildChildPath(normalizedParentPath, name)
        val writeResult = delegate(remotePath = targetPath).write(bytes, expectedVersion = null)
        FileSourceEntry(
            name = name,
            path = targetPath,
            isDirectory = false,
            versionToken = writeResult.versionToken,
            lastModified = writeResult.lastModified,
            sizeBytes = bytes.size.toLong()
        )
    }

    override suspend fun readFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        delegate(path).read()
    }

    suspend fun statPath(path: String): FileSourceStat? = withContext(Dispatchers.IO) {
        try {
            delegate(path).stat()
        } catch (error: OneDriveHttpException) {
            if (error.statusCode == 404) null else throw error
        }
    }

    suspend fun readFileTo(path: String, destination: File) = withContext(Dispatchers.IO) {
        delegate(path).readTo(destination)
    }

    suspend fun writeFileFrom(
        path: String,
        source: File,
        mode: MdbxRemoteWriteMode = MdbxRemoteWriteMode.CREATE_ONLY,
        expectedVersion: String? = null
    ): FileSourceWriteResult = withContext(Dispatchers.IO) {
        val parent = OneDriveKeePassFileSource.parentPathOf(path)
        if (parent.isNotBlank()) ensureDirectoryPath(parent)
        delegate(path).writeFrom(source, mode, expectedVersion)
    }

    suspend fun ensureDirectoryPath(path: String) = withContext(Dispatchers.IO) {
        val segments = OneDriveKeePassFileSource.normalizeOptionalRemotePath(path)
            .split('/')
            .filter(String::isNotBlank)
        var current = ""
        for (segment in segments) {
            val next = OneDriveKeePassFileSource.buildChildPath(current, segment)
            val existing = statPath(next)
            when {
                existing == null -> createDirectory(current.ifBlank { null }, segment)
                !existing.isDirectory -> error("OneDrive 远端路径不是目录: $next")
            }
            current = next
        }
    }
}
