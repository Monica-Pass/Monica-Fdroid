package takagi.ru.monica.utils

import takagi.ru.monica.R

import android.content.Context
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import takagi.ru.monica.data.KeepassRemoteSource
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.keepass.KeePassSourceChangedException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class KeePassLocalMirrorPaths(
    val workingCopyPath: String,
    val cacheCopyPath: String
)

class WebDavKeePassFileSource internal constructor(
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val remotePath: String? = null,
    private val strings: StringResolver
) : KeePassFileSource {
    private val normalizedServerUrl = serverUrl.trim().trimEnd('/')
    private val normalizedRemotePath = normalizeOptionalRemotePath(remotePath)
    private val remoteUrl = buildRemoteUrl(normalizedServerUrl, normalizedRemotePath)
    private val httpClient by lazy { OkHttpClient.Builder().build() }
    private val sardine by lazy { authenticatedSardine(httpClient) }

    private fun authenticatedSardine(client: OkHttpClient): OkHttpSardine =
        OkHttpSardine(client).apply {
            if (username.isNotBlank() || password.isNotBlank()) {
                setCredentials(username.trim(), password)
            }
        }

    override suspend fun stat(): FileSourceStat = withContext(Dispatchers.IO) {
        requireRemotePath()
        val resource = resolveResource(remoteUrl)
        if (resource != null) {
            val etag = resource.etag?.takeIf { it.isNotBlank() }
            return@withContext FileSourceStat(
                versionToken = etag ?: resource.modified?.time?.toString() ?: resource.contentLength?.toString(),
                etag = etag,
                lastModified = resource.modified?.time,
                sizeBytes = resource.contentLength,
                isDirectory = resource.isDirectory,
                displayName = resource.name
            )
        }

        val exists = webDavPathExists(remoteUrl)
        if (!exists) {
            throw IOException(strings.get(R.string.cloud_message_remote_file_missing, normalizedRemotePath))
        }

        FileSourceStat(
            versionToken = null,
            etag = null,
            lastModified = null,
            sizeBytes = null,
            isDirectory = false,
            displayName = normalizedRemotePath.substringAfterLast('/')
        )
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        requireRemotePath()
        if (!webDavPathExists(remoteUrl)) {
            throw IOException(strings.get(R.string.cloud_message_remote_file_missing, normalizedRemotePath))
        }
        sardine.get(remoteUrl).use { input ->
            input.readBytes()
        }
    }

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?
    ): FileSourceWriteResult = withContext(Dispatchers.IO) {
        requireRemotePath()
        val parentUrl = buildRemoteUrl(normalizedServerUrl, parentPathOf(normalizedRemotePath, strings = strings))
        if (parentUrl.isNotBlank() && !webDavPathExists(parentUrl)) {
            throw IOException(strings.get(R.string.cloud_message_remote_directory_missing, parentPathOf(normalizedRemotePath, strings = strings)))
        }

        val conditions = if (!expectedVersion.isNullOrBlank()) {
            val current = stat()
            if (!current.matchesExpectedVersion(expectedVersion)) {
                throw KeePassSourceChangedException(strings.get(R.string.cloud_message_remote_changed))
            }
            when {
                !current.etag.isNullOrBlank() && !current.etag.startsWith("W/") ->
                    mapOf("If-Match" to current.etag)
                current.lastModified != null -> mapOf(
                    "If-Unmodified-Since" to DateTimeFormatter.RFC_1123_DATE_TIME
                        .withZone(ZoneId.of("GMT")).format(Instant.ofEpochMilli(current.lastModified))
                )
                else -> throw IOException(strings.get(R.string.cloud_message_webdav_version_missing))
            }
        } else emptyMap()

        // Sardine 0.8 has no public byte-array PUT overload accepting headers.
        // A per-write client keeps the version condition on authentication retries too.
        val writer = if (conditions.isEmpty()) sardine else authenticatedSardine(
            httpClient.newBuilder().addInterceptor { chain ->
                val request = chain.request().newBuilder()
                if (chain.request().method == "PUT") {
                    conditions.forEach { (name, value) -> request.header(name, value) }
                }
                chain.proceed(request.build())
            }.build()
        )
        try {
            writer.put(remoteUrl, bytes, KEEPASS_KDBX_MIME_TYPE)
        } catch (error: SardineException) {
            if (error.statusCode == 412) {
                throw KeePassSourceChangedException(strings.get(R.string.cloud_message_remote_changed_merge)).apply { initCause(error) }
            }
            throw error
        }
        val latest = runCatching { stat() }.getOrDefault(FileSourceStat())
        FileSourceWriteResult(
            versionToken = latest.versionToken,
            etag = latest.etag,
            lastModified = latest.lastModified
        )
    }

    override suspend fun listChildren(): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        listDirectory(
            if (normalizedRemotePath.isBlank()) {
                ""
            } else {
                val stat = runCatching { stat() }.getOrNull()
                if (stat?.isDirectory == true) normalizedRemotePath else parentPathOf(normalizedRemotePath, strings = strings)
            }
        )
    }

    override suspend fun createFile(name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val targetPath = buildChildPath(parentPathOf(normalizedRemotePath, strings = strings), name, strings = strings)
        createFileInDirectory(parentPathOf(targetPath, strings = strings), name)
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = when {
                normalizedRemotePath.isBlank() -> ""
                runCatching { stat() }.getOrNull()?.isDirectory == true -> normalizedRemotePath
                else -> parentPathOf(normalizedRemotePath, strings = strings)
            }
            val targetUrl = buildRemoteUrl(normalizedServerUrl, targetDirectory).ifBlank { normalizedServerUrl }
            if (!webDavPathExists(targetUrl)) {
                throw IOException(strings.get(R.string.cloud_message_webdav_path_unavailable, targetUrl))
            }
            Unit
        }
    }

    suspend fun listDirectory(directoryPath: String? = null): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        val normalizedDirectoryPath = normalizeOptionalRemotePath(directoryPath)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, normalizedDirectoryPath).ifBlank { normalizedServerUrl }
        if (!webDavPathExists(targetUrl)) {
            throw IOException(
                if (normalizedDirectoryPath.isBlank()) {
                    strings.get(R.string.cloud_message_webdav_root_unavailable)
                } else {
                    strings.get(R.string.cloud_message_remote_directory_missing, normalizedDirectoryPath)
                }
            )
        }
        sardine.list(targetUrl)
            .filterNot { resource ->
                normalizeResourceUrl(resource.href?.toString())
                    .equals(normalizeResourceUrl(targetUrl), ignoreCase = true)
            }
            .map { resource ->
                FileSourceEntry(
                    id = resource.href?.toString(),
                    name = resource.name,
                    path = buildChildPath(normalizedDirectoryPath, resource.name, strings = strings),
                    isDirectory = resource.isDirectory,
                    versionToken = resource.etag?.takeIf { it.isNotBlank() }
                        ?: resource.modified?.time?.toString()
                        ?: resource.contentLength?.toString(),
                    lastModified = resource.modified?.time,
                    sizeBytes = resource.contentLength
                )
            }
            .sortedWith(
                compareBy<FileSourceEntry> { !it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    suspend fun createDirectory(parentPath: String?, name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = normalizeOptionalRemotePath(parentPath)
        val targetPath = buildChildPath(normalizedParentPath, name, strings = strings)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, targetPath)
        if (webDavPathExists(targetUrl)) {
            throw IOException(strings.get(R.string.cloud_message_folder_exists))
        }
        sardine.createDirectory(targetUrl)
        FileSourceEntry(
            id = targetUrl,
            name = name.trim(),
            path = targetPath,
            isDirectory = true
        )
    }

    suspend fun createFileInDirectory(
        parentPath: String?,
        name: String,
        bytes: ByteArray = ByteArray(0)
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = normalizeOptionalRemotePath(parentPath)
        val targetPath = buildChildPath(normalizedParentPath, name, strings = strings)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, targetPath)
        val parentUrl = buildRemoteUrl(normalizedServerUrl, normalizedParentPath)
        if (parentUrl.isNotBlank() && !webDavPathExists(parentUrl)) {
            throw IOException(
                if (normalizedParentPath.isBlank()) {
                    strings.get(R.string.cloud_message_remote_directory_unavailable)
                } else {
                    strings.get(R.string.cloud_message_remote_directory_missing, normalizedParentPath)
                }
            )
        }
        if (webDavPathExists(targetUrl)) {
            throw IOException(strings.get(R.string.cloud_message_file_exists))
        }
        sardine.put(targetUrl, bytes, KEEPASS_KDBX_MIME_TYPE)
        val latest = runCatching { resolveResource(targetUrl) }.getOrNull()
        FileSourceEntry(
            id = latest?.href?.toString() ?: targetUrl,
            name = latest?.name ?: name.trim(),
            path = targetPath,
            isDirectory = false,
            versionToken = latest?.etag?.takeIf { it.isNotBlank() }
                ?: latest?.modified?.time?.toString()
                ?: latest?.contentLength?.toString(),
            lastModified = latest?.modified?.time,
            sizeBytes = latest?.contentLength?.takeIf { it >= 0L } ?: bytes.size.toLong()
        )
    }

    private fun resolveResource(targetUrl: String): DavResource? {
        val directResources = runCatching { sardine.list(targetUrl) }.getOrNull().orEmpty()
        directResources.firstOrNull { resource ->
            normalizeResourceUrl(resource.href?.toString()).equals(
                normalizeResourceUrl(targetUrl),
                ignoreCase = true
            )
        }?.let { return it }
        directResources.firstOrNull()?.let { return it }

        val parentUrl = buildRemoteUrl(normalizedServerUrl, parentPathOf(normalizedRemotePath, strings = strings))
        if (parentUrl.isBlank()) return null
        val fileName = normalizedRemotePath.substringAfterLast('/')
        return runCatching { sardine.list(parentUrl) }
            .getOrNull()
            .orEmpty()
            .firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
    }

    private fun normalizeResourceUrl(url: String?): String {
        return url.orEmpty().trimEnd('/')
    }

    private fun webDavPathExists(targetUrl: String): Boolean {
        runCatching { sardine.exists(targetUrl) }
            .onSuccess { return it }
        return runCatching { sardine.list(targetUrl) }
            .map { true }
            .getOrElse { false }
    }

    private fun requireRemotePath() {
        if (normalizedRemotePath.isBlank()) {
            throw IllegalStateException(strings.get(R.string.cloud_message_path_required))
        }
    }

    private fun FileSourceStat.matchesExpectedVersion(expectedVersion: String): Boolean {
        val expected = expectedVersion.trim()
        val currentVersion = etag ?: versionToken ?: lastModified?.toString()
        return expected.isBlank() || expected == currentVersion
    }

    companion object {
        internal fun normalizeRemotePath(remotePath: String, strings: StringResolver): String {
            val normalized = remotePath
                .trim()
                .replace('\\', '/')
                .trimStart('/')
                .replace(Regex("/+"), "/")
            if (normalized.isBlank()) {
                throw IllegalArgumentException(strings.get(R.string.cloud_message_path_required))
            }
            return normalized
        }

        fun normalizeOptionalRemotePath(remotePath: String?): String {
            val normalized = remotePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                ?.replace(Regex("/+"), "/")
                .orEmpty()
            return normalized
        }

        fun buildRemoteUrl(serverUrl: String, remotePath: String?): String {
            val normalizedServerUrl = serverUrl.trim().trimEnd('/')
            val normalizedPath = remotePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                .orEmpty()
            return if (normalizedPath.isBlank()) {
                normalizedServerUrl
            } else {
                "$normalizedServerUrl/$normalizedPath"
            }
        }

        internal fun parentPathOf(remotePath: String, strings: StringResolver): String {
            if (remotePath.isBlank()) {
                return ""
            }
            val normalized = normalizeRemotePath(remotePath, strings = strings)
            val index = normalized.lastIndexOf('/')
            return if (index <= 0) "" else normalized.substring(0, index)
        }

        internal fun buildChildPath(parentPath: String, name: String, strings: StringResolver): String {
            val sanitizedName = name.trim().trim('/').ifBlank {
                throw IllegalArgumentException(strings.get(R.string.cloud_message_filename_required))
            }
            require('/' !in sanitizedName) { strings.get(R.string.cloud_message_filename_separator) }
            return if (parentPath.isBlank()) sanitizedName else "$parentPath/$sanitizedName"
        }

    }
}

object WebDavKeePassSupport {
    internal fun createFileSource(
        source: KeepassRemoteSource,
        securityManager: SecurityManager,
        strings: StringResolver
    ): WebDavKeePassFileSource {
        require(source.baseUrl?.isNotBlank() == true) { strings.get(R.string.cloud_message_webdav_url_required) }
        val username = source.usernameEncrypted?.let { securityManager.decryptData(it) }.orEmpty()
        val password = source.passwordEncrypted?.let { securityManager.decryptData(it) }.orEmpty()
        return WebDavKeePassFileSource(
            serverUrl = source.baseUrl,
            username = username,
            password = password,
            remotePath = source.remotePath,
            strings = strings,
        )
    }

    internal fun buildLocalMirrorPaths(sourceId: Long, remotePath: String, strings: StringResolver): KeePassLocalMirrorPaths {
        val fileName = displayNameFromRemotePath(remotePath, strings = strings)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "remote.kdbx" }
        val baseDir = "keepass_remote/webdav_$sourceId"
        return KeePassLocalMirrorPaths(
            workingCopyPath = "$baseDir/working_$fileName",
            cacheCopyPath = "$baseDir/cache_$fileName"
        )
    }

    internal fun displayNameFromRemotePath(remotePath: String, strings: StringResolver): String {
        val normalized = WebDavKeePassFileSource.normalizeRemotePath(remotePath, strings = strings)
        return normalized.substringAfterLast('/').ifBlank { "remote.kdbx" }
    }

    fun writeRelativeFile(
        context: Context,
        relativePath: String,
        bytes: ByteArray
    ) {
        val strings = AppLocaleStringResolver(context)
        val file = File(context.filesDir, relativePath)
        val parent = file.parentFile ?: throw IOException(strings.get(R.string.storage_error_invalid_path))
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent, "${file.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            throw IOException(strings.get(R.string.cloud_message_working_copy_replace))
        }
        if (!tempFile.renameTo(file)) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            tempFile.delete()
        }
    }

    fun deleteRelativeFile(context: Context, relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        val file = File(context.filesDir, relativePath)
        if (file.exists()) {
            file.delete()
        }
        file.parentFile?.takeIf { it.exists() && it.listFiles().isNullOrEmpty() }?.delete()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }
}
