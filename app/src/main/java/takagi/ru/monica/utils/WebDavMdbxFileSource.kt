package takagi.ru.monica.utils

import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.webdav.WebDavCredentials
import takagi.ru.monica.webdav.WebDavGateway
import takagi.ru.monica.webdav.WebDavErrorClassifier
import takagi.ru.monica.webdav.WebDavErrorKind
import takagi.ru.monica.webdav.WebDavConditionalWriter
import takagi.ru.monica.webdav.WebDavPreconditionException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WebDavMdbxFileSource(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) : MdbxFileSource {

    private val normalizedServerUrl = serverUrl.trim().trimEnd('/')
    private val credentials = WebDavCredentials(username, password)
    private val httpClient by lazy { WebDavGateway.buildHttpClient(credentials, normalizedServerUrl) }
    private val sardine: OkHttpSardine by lazy { OkHttpSardine(httpClient) }
    private val conditionalWriter by lazy { WebDavConditionalWriter(httpClient) }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sardine.list(normalizedServerUrl, 0)
        }.map { Unit }
    }

    override suspend fun listDirectory(path: String?): List<FileSourceEntry> =
        withContext(Dispatchers.IO) {
            val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(
                normalizedServerUrl, path
            )
            val resources: List<DavResource> = sardine.list(targetUrl)
            resources.filter { it.name != "." && it.name != ".." }.map { resource ->
                FileSourceEntry(
                    name = resource.name,
                    path = WebDavKeePassFileSource.buildChildPath(
                        WebDavKeePassFileSource.normalizeOptionalRemotePath(path), resource.name
                    ),
                    isDirectory = resource.isDirectory,
                    lastModified = resource.modified?.time,
                    sizeBytes = resource.contentLength
                )
            }.sortedWith(
                compareByDescending<FileSourceEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
        }

    override suspend fun createDirectory(
        parentPath: String?,
        name: String
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val sanitizedName = name.trim().trim('/').ifBlank {
            throw IOException("目录名不能为空")
        }
        val normalizedParent = WebDavKeePassFileSource.normalizeOptionalRemotePath(parentPath)
        val targetPath = if (normalizedParent.isBlank()) sanitizedName
        else "$normalizedParent/$sanitizedName"
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, targetPath)

        if (webDavPathExists(targetUrl)) {
            throw IOException("目录已存在: $sanitizedName")
        }

        sardine.createDirectory(targetUrl)
        FileSourceEntry(
            name = sanitizedName,
            path = targetPath,
            isDirectory = true
        )
    }

    override suspend fun createPlaceholderFile(
        parentPath: String?,
        name: String
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        writeFile(parentPath, name, ByteArray(0))
    }

    override suspend fun writeFile(
        parentPath: String?,
        name: String,
        bytes: ByteArray
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val sanitizedName = name.trim().trim('/').ifBlank {
            throw IOException("文件名不能为空")
        }
        val normalizedParent = WebDavKeePassFileSource.normalizeOptionalRemotePath(parentPath)
        val targetPath = if (normalizedParent.isBlank()) sanitizedName
        else "$normalizedParent/$sanitizedName"
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, targetPath)

        if (webDavPathExists(targetUrl)) {
            throw IOException("文件已存在: $sanitizedName")
        }

        // Auto-create intermediate directories
        if (normalizedParent.isNotBlank()) {
            ensureDirectoryPathExists(normalizedParent)
        }

        sardine.put(targetUrl, bytes, "application/octet-stream")
        FileSourceEntry(
            name = sanitizedName,
            path = targetPath,
            isDirectory = false,
            sizeBytes = bytes.size.toLong()
        )
    }

    override suspend fun readFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, path)
        sardine.get(targetUrl).use { input -> input.readBytes() }
    }

    /** File-to-file download used by MDBX2; avoids keeping a Blob in the JVM heap. */
    suspend fun readFileTo(path: String, destination: File) = withContext(Dispatchers.IO) {
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        require(normalizedPath.isNotBlank()) { "远端文件路径不能为空" }
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        val parent = destination.parentFile ?: throw IOException("下载目标目录不存在")
        check(parent.exists() || parent.mkdirs()) { "无法创建下载目标目录" }
        val temporary = File.createTempFile(".mdbx-download-", ".tmp", parent)
        try {
            sardine.get(targetUrl).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (!temporary.renameTo(destination)) {
                    throw IOException("无法发布 WebDAV 下载文件")
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Returns metadata for an exact path, or null when the path is absent. */
    suspend fun statPath(path: String): FileSourceEntry? = withContext(Dispatchers.IO) {
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        if (normalizedPath.isBlank()) return@withContext null
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        val direct = listOrEmptyWhenNotFound { sardine.list(targetUrl) }
        val directMatch = direct.firstOrNull { resource ->
            normalizeResourceUrl(resource.href?.toString())
                .equals(normalizeResourceUrl(targetUrl), ignoreCase = true)
        } ?: direct.firstOrNull { it.name.equals(normalizedPath.substringAfterLast('/'), ignoreCase = true) }
        val resource = directMatch ?: run {
            val parentPath = WebDavKeePassFileSource.parentPathOf(normalizedPath)
            val parentUrl = WebDavMdbxSourceUrl(normalizedServerUrl, parentPath)
            listOrEmptyWhenNotFound { sardine.list(parentUrl) }
                .firstOrNull { it.name.equals(normalizedPath.substringAfterLast('/'), ignoreCase = true) }
        } ?: return@withContext null
        FileSourceEntry(
            id = resource.href?.toString(),
            name = resource.name,
            path = normalizedPath,
            isDirectory = resource.isDirectory,
            // Only an ETag is a valid If-Match token. Timestamps and sizes are
            // useful metadata but cannot safely protect a conditional PUT.
            versionToken = resource.etag?.takeIf { it.isNotBlank() },
            lastModified = resource.modified?.time,
            sizeBytes = resource.contentLength
        )
    }

    /**
     * Uploads an immutable or conditionally replaced object from disk.
     * CREATE_ONLY treats an existing byte-identical object as success and
     * rejects a same-name/different-content collision.
     */
    suspend fun writeFileFrom(
        path: String,
        source: File,
        mode: MdbxRemoteWriteMode = MdbxRemoteWriteMode.CREATE_ONLY,
        expectedVersion: String? = null
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        MdbxRemoteSyncPaths.requireRegularFile(source)
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        require(normalizedPath.isNotBlank()) { "文件名不能为空" }
        val parentPath = WebDavKeePassFileSource.parentPathOf(normalizedPath)
        if (parentPath.isNotBlank()) ensureDirectoryPathExists(parentPath)
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        val existing = statPath(normalizedPath)
        if (mode == MdbxRemoteWriteMode.CREATE_ONLY && existing != null) {
            if (isSameImmutableContent(normalizedPath, existing, source)) return@withContext existing
            throw IOException("远端不可变对象已存在但内容不同: $normalizedPath")
        }
        if (mode == MdbxRemoteWriteMode.IF_MATCH) {
            val requiredVersion = expectedVersion?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("WebDAV conditional replacement requires an ETag")
            if (existing?.versionToken != requiredVersion) {
                throw IOException("远端文件已变化，请先重新同步")
            }
        }
        try {
            conditionalWriter.write(targetUrl, source, mode, expectedVersion)
        } catch (error: WebDavPreconditionException) {
            if (mode == MdbxRemoteWriteMode.CREATE_ONLY) {
                val raced = statPath(normalizedPath)
                if (raced != null && isSameImmutableContent(normalizedPath, raced, source)) {
                    return@withContext raced
                }
                throw IOException("远端不可变对象已存在但内容不同: $normalizedPath", error)
            }
            throw IOException("远端文件已变化，请先重新同步", error)
        }
        statPath(normalizedPath) ?: FileSourceEntry(
            name = normalizedPath.substringAfterLast('/'),
            path = normalizedPath,
            isDirectory = false,
            sizeBytes = source.length()
        )
    }

    private suspend fun isSameImmutableContent(
        normalizedPath: String,
        existing: FileSourceEntry,
        source: File
    ): Boolean {
        if (existing.isDirectory) throw IOException("远端路径已是目录: $normalizedPath")
        if (existing.sizeBytes != null && existing.sizeBytes != source.length()) return false
        val comparisonDirectory = source.parentFile
            ?: throw IOException("MDBX2 comparison source has no parent directory")
        val temporary = File.createTempFile(".mdbx-compare-", ".tmp", comparisonDirectory)
        return try {
            readFileTo(normalizedPath, temporary)
            temporary.length() == source.length() &&
                MdbxRemoteSyncPaths.sha256Hex(temporary) == MdbxRemoteSyncPaths.sha256Hex(source)
        } finally {
            temporary.delete()
        }
    }

    suspend fun ensureDirectoryPath(path: String) = withContext(Dispatchers.IO) {
        ensureDirectoryPathExists(WebDavKeePassFileSource.normalizeOptionalRemotePath(path))
    }

    suspend fun overwriteFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(path)
        val targetUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, normalizedPath)
        sardine.put(targetUrl, bytes, "application/octet-stream")
    }

    /**
     * Recursively create intermediate directories on WebDAV server.
     * Skips directories that already exist.
     */
    private fun ensureDirectoryPathExists(path: String) {
        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return

        var accumulatedPath = ""
        for (segment in segments) {
            accumulatedPath = if (accumulatedPath.isEmpty()) segment else "$accumulatedPath/$segment"
            val dirUrl = WebDavKeePassFileSource.buildRemoteUrl(normalizedServerUrl, accumulatedPath)
            try {
                sardine.createDirectory(dirUrl)
            } catch (_: IOException) {
                // Directory may already exist, try listing to confirm
                try {
                    sardine.list(dirUrl, 0)
                } catch (_: IOException) {
                    throw IOException("无法创建远程目录: $accumulatedPath")
                }
            }
        }
    }

    private fun normalizeResourceUrl(url: String?): String = url.orEmpty().trimEnd('/')

    private fun WebDavMdbxSourceUrl(server: String, path: String): String =
        WebDavKeePassFileSource.buildRemoteUrl(server, path).ifBlank { server }

    private fun webDavPathExists(targetUrl: String): Boolean {
        return try {
            sardine.list(targetUrl, 1)
            true
        } catch (error: Throwable) {
            if (isNotFound(error)) false else throw error
        }
    }

    private fun <T> listOrEmptyWhenNotFound(block: () -> List<T>): List<T> {
        return try {
            block()
        } catch (error: Throwable) {
            if (isNotFound(error)) emptyList() else throw error
        }
    }

    private fun isNotFound(error: Throwable): Boolean =
        WebDavErrorClassifier.classify(error).kind == WebDavErrorKind.NotFound
}
