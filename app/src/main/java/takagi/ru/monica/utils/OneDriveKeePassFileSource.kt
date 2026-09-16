package takagi.ru.monica.utils

import takagi.ru.monica.keepass.KeePassSourceChangedException

import takagi.ru.monica.R

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import takagi.ru.monica.data.KeepassRemoteSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale

internal class OneDriveHttpException(
    val statusCode: Int,
    responseBody: String,
    strings: StringResolver
) : IOException(
    "HTTP $statusCode: " + responseBody.ifBlank { strings.get(R.string.cloud_message_provider_request_failed, "OneDrive") }
)

internal fun interface OneDriveAccessTokenProvider {
    suspend fun acquire(): String
}

private class MsalOneDriveAccessTokenProvider(
    context: Context,
    private val accountIdentifier: String
) : OneDriveAccessTokenProvider {
    private val strings = AppLocaleStringResolver(context)
    private val authManager = OneDriveAuthManager(context.applicationContext)

    override suspend fun acquire(): String =
        authManager.acquireAccessToken(accountIdentifier).accessToken
            ?: throw IOException(strings.get(R.string.cloud_message_provider_token_missing, "OneDrive"))
}

private data class OneDriveFileSourceDependencies(
    val accessTokenProvider: OneDriveAccessTokenProvider,
    val httpClient: OkHttpClient,
    val graphBaseUrl: String,
    val cacheDirectory: File,
    val strings: StringResolver
)

@Serializable
private data class OneDriveDriveItemDto(
    val id: String,
    val name: String,
    val size: Long? = null,
    @SerialName("eTag")
    val eTag: String? = null,
    @SerialName("cTag")
    val cTag: String? = null,
    @SerialName("lastModifiedDateTime")
    val lastModifiedDateTime: String? = null,
    val folder: FolderFacetDto? = null,
    val file: FileFacetDto? = null,
    @SerialName("parentReference")
    val parentReference: ParentReferenceDto? = null
)

@Serializable
private data class OneDriveChildrenResponseDto(
    val value: List<OneDriveDriveItemDto> = emptyList(),
    @SerialName("@odata.nextLink")
    val nextLink: String? = null
)

@Serializable
private data class FolderFacetDto(
    @SerialName("childCount")
    val childCount: Int? = null
)

@Serializable
private data class FileFacetDto(
    val mimeType: String? = null
)

@Serializable
private data class ParentReferenceDto(
    @SerialName("driveId")
    val driveId: String? = null,
    val path: String? = null
)

@Serializable
private data class OneDriveUploadSessionResponseDto(
    @SerialName("uploadUrl")
    val uploadUrl: String,
    @SerialName("expirationDateTime")
    val expirationDateTime: String? = null,
    @SerialName("nextExpectedRanges")
    val nextExpectedRanges: List<String> = emptyList()
)

@Serializable
private data class OneDriveUploadSessionRequestDto(
    val item: OneDriveUploadSessionItemDto = OneDriveUploadSessionItemDto()
)

@Serializable
private data class OneDriveUploadSessionItemDto(
    @SerialName("@microsoft.graph.conflictBehavior")
    val conflictBehavior: String = "replace"
)

class OneDriveKeePassFileSource private constructor(
    private val accountIdentifier: String,
    private val driveId: String? = null,
    private val itemId: String? = null,
    private val remotePath: String? = null,
    dependencies: OneDriveFileSourceDependencies
) : KeePassFileSource {
    private val accessTokenProvider = dependencies.accessTokenProvider
    private val httpClient = dependencies.httpClient
    private val graphBaseUrl = dependencies.graphBaseUrl
    private val cacheDirectory = dependencies.cacheDirectory
    private val strings = dependencies.strings
    private val normalizedRemotePath = normalizeOptionalRemotePath(remotePath)
    private val json = Json { ignoreUnknownKeys = true }

    constructor(
        context: Context,
        accountIdentifier: String,
        driveId: String? = null,
        itemId: String? = null,
        remotePath: String? = null
    ) : this(
        accountIdentifier = accountIdentifier,
        driveId = driveId,
        itemId = itemId,
        remotePath = remotePath,
        dependencies = OneDriveFileSourceDependencies(
            accessTokenProvider = MsalOneDriveAccessTokenProvider(context, accountIdentifier),
            httpClient = sharedHttpClient,
            graphBaseUrl = GRAPH_BASE_URL,
            cacheDirectory = context.applicationContext.cacheDir,
            strings = AppLocaleStringResolver(context)
        )
    )

    internal constructor(
        accountIdentifier: String,
        driveId: String? = null,
        itemId: String? = null,
        remotePath: String? = null,
        accessTokenProvider: OneDriveAccessTokenProvider,
        httpClient: OkHttpClient,
        graphBaseUrl: String,
        cacheDirectory: File,
        strings: StringResolver
    ) : this(
        accountIdentifier = accountIdentifier,
        driveId = driveId,
        itemId = itemId,
        remotePath = remotePath,
        dependencies = OneDriveFileSourceDependencies(
            accessTokenProvider = accessTokenProvider,
            httpClient = httpClient,
            graphBaseUrl = graphBaseUrl.trimEnd('/'),
            cacheDirectory = cacheDirectory,
            strings = strings
        )
    )

    override suspend fun stat(): FileSourceStat = withContext(Dispatchers.IO) {
        requireRemotePath()
        resolveFileItem().toStat()
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        requireRemotePath()
        val token = accessToken()
        executeBytesRequest(
            relativeUrl = buildItemContentRelativeUrl(),
            accessToken = token
        )
    }

    /** Stream a OneDrive object to an atomically published local file. */
    suspend fun readTo(destination: File) = withContext(Dispatchers.IO) {
        requireRemotePath()
        val token = accessToken()
        val parent = destination.parentFile ?: throw IOException(strings.get(R.string.cloud_message_download_directory_missing))
        check(parent.exists() || parent.mkdirs()) { strings.get(R.string.cloud_message_download_directory_create) }
        val temporary = File.createTempFile(".mdbx-download-", ".tmp", parent)
        try {
            val request = Request.Builder()
                .url(resolveGraphUrl(buildItemContentRelativeUrl()))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw OneDriveHttpException(response.code, response.body?.string().orEmpty(), strings = strings)
                }
                response.body?.byteStream()?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException(strings.get(R.string.cloud_message_provider_empty_content, "OneDrive"))
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
                    throw IOException(strings.get(R.string.cloud_message_download_finalize, "OneDrive"))
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** File-based upload for MDBX2 bootstrap, segments and content-addressed Blobs. */
    suspend fun writeFrom(
        source: File,
        mode: MdbxRemoteWriteMode = MdbxRemoteWriteMode.CREATE_ONLY,
        expectedVersion: String? = null
    ): FileSourceWriteResult = withContext(Dispatchers.IO) {
        requireRemotePath()
        MdbxRemoteSyncPaths.requireRegularFile(source)
        val existing = try {
            stat()
        } catch (error: OneDriveHttpException) {
            if (error.statusCode == 404) null else throw error
        }
        if (mode == MdbxRemoteWriteMode.CREATE_ONLY && existing != null) {
            if (existing.isDirectory) throw IOException(strings.get(R.string.cloud_message_remote_is_directory))
            val temporary = File.createTempFile(".mdbx-compare-", ".tmp", cacheDirectory)
            try {
                readTo(temporary)
                if (temporary.length() == source.length() &&
                    MdbxRemoteSyncPaths.sha256Hex(temporary) == MdbxRemoteSyncPaths.sha256Hex(source)
                ) {
                    return@withContext FileSourceWriteResult(
                        versionToken = existing.versionToken,
                        etag = existing.etag,
                        lastModified = existing.lastModified,
                        remoteId = existing.remoteId,
                        driveId = existing.driveId
                    )
                }
            } finally {
                temporary.delete()
            }
            throw IOException(strings.get(R.string.cloud_message_immutable_differs))
        }
        if (mode == MdbxRemoteWriteMode.IF_MATCH) {
            val requiredVersion = expectedVersion?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException(
                    "OneDrive conditional replacement requires an ETag"
                )
            if (existing?.versionToken != requiredVersion && existing?.etag != requiredVersion) {
                throw KeePassSourceChangedException(strings.get(R.string.cloud_message_remote_changed))
            }
        }
        val token = accessToken()
        val headers = when {
            mode == MdbxRemoteWriteMode.IF_MATCH -> mapOf("If-Match" to expectedVersion!!)
            else -> emptyMap()
        }
        if (source.length() > LARGE_UPLOAD_THRESHOLD_BYTES) {
            return@withContext uploadLargeFileFrom(
                accessToken = token,
                source = source,
                headers = headers,
                createOnly = mode == MdbxRemoteWriteMode.CREATE_ONLY
            )
        }
        val relativeUrl = if (mode == MdbxRemoteWriteMode.CREATE_ONLY) {
            buildPathContentRelativeUrl(normalizedRemotePath, conflictBehavior = "fail")
        } else {
            buildItemContentRelativeUrl()
        }
        val payload = executeJsonRequest(
            relativeUrl = relativeUrl,
            accessToken = token,
            method = "PUT",
            body = source.readBytes(),
            headers = headers,
            expectedStatusCodes = setOf(200, 201)
        )
        json.decodeFromString<OneDriveDriveItemDto>(payload).toWriteResult()
    }

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?
    ): FileSourceWriteResult = withContext(Dispatchers.IO) {
        requireRemotePath()
        val token = accessToken()
        val headers = expectedVersion
            ?.takeIf { it.isNotBlank() }
            ?.let { mapOf("If-Match" to it) }
            .orEmpty()
        if (bytes.size > LARGE_UPLOAD_THRESHOLD_BYTES) {
            return@withContext uploadLargeFile(
                accessToken = token,
                bytes = bytes,
                headers = headers
            )
        }
        val latest = executeJsonRequest(
            relativeUrl = buildItemContentRelativeUrl(),
            accessToken = token,
            method = "PUT",
            body = bytes,
            headers = headers,
            expectedStatusCodes = setOf(200, 201)
        )
        json.decodeFromString<OneDriveDriveItemDto>(latest).toWriteResult()
    }

    override suspend fun listChildren(): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        val targetDirectory = when {
            normalizedRemotePath.isBlank() -> ""
            runCatching { stat() }.getOrNull()?.isDirectory == true -> normalizedRemotePath
            else -> parentPathOf(normalizedRemotePath, strings = strings)
        }
        listDirectory(targetDirectory)
    }

    override suspend fun createFile(name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val targetParent = parentPathOf(normalizedRemotePath, strings = strings)
        createFileInDirectory(targetParent, name)
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = accessToken()
            val relativeUrl = if (normalizedRemotePath.isBlank()) {
                "${driveBaseRelativeUrl()}/root/children"
            } else {
                val item = resolveItemByPath(normalizedRemotePath)
                if (item.folder != null) {
                    buildChildrenRelativeUrl(normalizedRemotePath)
                } else {
                    val parent = parentPathOf(normalizedRemotePath, strings = strings)
                    if (parent.isBlank()) {
                        "${driveBaseRelativeUrl()}/root/children"
                    } else {
                        buildChildrenRelativeUrl(parent)
                    }
                }
            }
            executeJsonRequest(relativeUrl = relativeUrl, accessToken = token)
            Unit
        }
    }

    suspend fun listDirectory(directoryPath: String? = null): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        val normalizedDirectoryPath = normalizeOptionalRemotePath(directoryPath)
        val token = accessToken()
        val items = mutableListOf<OneDriveDriveItemDto>()
        var nextUrl: String? = buildChildrenRelativeUrl(normalizedDirectoryPath)
        while (nextUrl != null) {
            val payload = executeJsonRequest(
                relativeUrl = nextUrl,
                accessToken = token
            )
            val page = json.decodeFromString<OneDriveChildrenResponseDto>(payload)
            items += page.value
            nextUrl = page.nextLink
        }
        items
            .map { item ->
                FileSourceEntry(
                    id = item.id,
                    name = item.name,
                    path = buildChildPath(normalizedDirectoryPath, item.name, strings = strings),
                    isDirectory = item.folder != null,
                    versionToken = item.eTag ?: item.cTag,
                    lastModified = item.lastModifiedDateTime?.toEpochMillis(),
                    sizeBytes = item.size
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
        if (pathExists(targetPath)) {
            throw IOException(strings.get(R.string.cloud_message_folder_exists))
        }
        val token = accessToken()
        val payload = executeJsonRequest(
            relativeUrl = resolveCreateFolderRelativeUrl(normalizedParentPath),
            accessToken = token,
            method = "POST",
            body = json.encodeToString(
                FolderCreateRequest.serializer(),
                FolderCreateRequest(
                    name = name.trim(),
                    folder = FolderCreateFacet(),
                    conflictBehavior = "fail"
                )
            ).encodeToByteArray(),
            contentType = "application/json; charset=utf-8",
            expectedStatusCodes = setOf(200, 201)
        )
        val item = json.decodeFromString<OneDriveDriveItemDto>(payload)
        FileSourceEntry(
            id = item.id,
            name = item.name,
            path = targetPath,
            isDirectory = true,
            versionToken = item.eTag ?: item.cTag,
            lastModified = item.lastModifiedDateTime?.toEpochMillis(),
            sizeBytes = item.size
        )
    }

    suspend fun createFileInDirectory(
        parentPath: String?,
        name: String,
        bytes: ByteArray = ByteArray(0)
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = normalizeOptionalRemotePath(parentPath)
        val targetPath = buildChildPath(normalizedParentPath, name, strings = strings)
        if (pathExists(targetPath)) {
            throw IOException(strings.get(R.string.cloud_message_file_exists))
        }
        val token = accessToken()
        val payload = executeJsonRequest(
            relativeUrl = buildPathContentRelativeUrl(
                path = targetPath,
                conflictBehavior = "fail"
            ),
            accessToken = token,
            method = "PUT",
            body = bytes,
            expectedStatusCodes = setOf(200, 201)
        )
        val item = json.decodeFromString<OneDriveDriveItemDto>(payload)
        return@withContext FileSourceEntry(
            id = item.id,
            name = item.name,
            path = targetPath,
            isDirectory = false,
            versionToken = item.eTag ?: item.cTag,
            lastModified = item.lastModifiedDateTime?.toEpochMillis(),
            sizeBytes = item.size ?: bytes.size.toLong()
        )
    }

    suspend fun deleteEntry(targetPath: String) = withContext(Dispatchers.IO) {
        val normalizedTargetPath = normalizeRemotePath(targetPath, strings = strings)
        val item = resolveItemByPath(normalizedTargetPath)
        val token = accessToken()
        executeJsonRequest(
            relativeUrl = "${driveBaseRelativeUrl()}/items/${encodePathSegment(item.id)}",
            accessToken = token,
            method = "DELETE",
            expectedStatusCodes = setOf(204)
        )
    }

    suspend fun renameEntry(targetPath: String, newName: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedTargetPath = normalizeRemotePath(targetPath, strings = strings)
        val sanitizedName = newName.trim().trim('/').ifBlank {
            throw IllegalArgumentException(strings.get(R.string.cloud_message_filename_required))
        }
        require('/' !in sanitizedName) { strings.get(R.string.cloud_message_filename_separator) }
        val item = resolveItemByPath(normalizedTargetPath)
        val token = accessToken()
        val payload = executeJsonRequest(
            relativeUrl = "${driveBaseRelativeUrl()}/items/${encodePathSegment(item.id)}",
            accessToken = token,
            method = "PATCH",
            body = json.encodeToString(
                RenameItemRequest.serializer(),
                RenameItemRequest(name = sanitizedName)
            ).encodeToByteArray(),
            contentType = "application/json; charset=utf-8",
            expectedStatusCodes = setOf(200)
        )
        val updated = json.decodeFromString<OneDriveDriveItemDto>(payload)
        FileSourceEntry(
            id = updated.id,
            name = updated.name,
            path = buildChildPath(parentPathOf(normalizedTargetPath, strings = strings), updated.name, strings = strings),
            isDirectory = updated.folder != null,
            versionToken = updated.eTag ?: updated.cTag,
            lastModified = updated.lastModifiedDateTime?.toEpochMillis(),
            sizeBytes = updated.size
        )
    }

    private suspend fun resolveFileItem(): OneDriveDriveItemDto {
        return itemId?.takeIf { it.isNotBlank() }
            ?.let { resolveItemById(it) }
            ?: resolveItemByPath(normalizedRemotePath)
    }

    private suspend fun resolveItemById(resolvedItemId: String): OneDriveDriveItemDto {
        val token = accessToken()
        val payload = executeJsonRequest(
            relativeUrl = "${driveBaseRelativeUrl()}/items/${encodePathSegment(resolvedItemId)}",
            accessToken = token
        )
        return json.decodeFromString(payload)
    }

    private suspend fun resolveItemByPath(path: String): OneDriveDriveItemDto {
        if (path.isBlank()) {
            val token = accessToken()
            val payload = executeJsonRequest(
                relativeUrl = "${driveBaseRelativeUrl()}/root",
                accessToken = token
            )
            return json.decodeFromString(payload)
        }
        val token = accessToken()
        val payload = executeJsonRequest(
            relativeUrl = buildPathMetadataRelativeUrl(path),
            accessToken = token
        )
        return json.decodeFromString(payload)
    }

    private suspend fun uploadLargeFile(
        accessToken: String,
        bytes: ByteArray,
        headers: Map<String, String>
    ): FileSourceWriteResult {
        val sessionPayload = executeJsonRequest(
            relativeUrl = buildUploadSessionRelativeUrl(),
            accessToken = accessToken,
            method = "POST",
            body = json.encodeToString(
                OneDriveUploadSessionRequestDto.serializer(),
                OneDriveUploadSessionRequestDto()
            ).encodeToByteArray(),
            contentType = "application/json; charset=utf-8",
            headers = headers,
            expectedStatusCodes = setOf(200)
        )
        val session = json.decodeFromString<OneDriveUploadSessionResponseDto>(sessionPayload)
        var offset = 0
        while (offset < bytes.size) {
            val endExclusive = minOf(offset + UPLOAD_CHUNK_SIZE_BYTES, bytes.size)
            val chunk = bytes.copyOfRange(offset, endExclusive)
            val request = Request.Builder()
                .url(session.uploadUrl)
                .header("Content-Range", "bytes $offset-${endExclusive - 1}/${bytes.size}")
                .header("Content-Length", chunk.size.toString())
                .put(chunk.toRequestBody(KEEPASS_KDBX_MIME_TYPE.toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                when (response.code) {
                    200, 201 -> {
                        val item = json.decodeFromString<OneDriveDriveItemDto>(responseBody)
                        return item.toWriteResult()
                    }
                    202 -> {
                        offset = endExclusive
                    }
                    412 -> throw KeePassSourceChangedException(strings.get(R.string.cloud_message_remote_changed))
                    else -> throw IOException(
                        responseBody.ifBlank { strings.get(R.string.cloud_message_upload_http, "OneDrive", response.code) }
                    )
                }
            }
        }
        throw IOException(strings.get(R.string.cloud_message_upload_unconfirmed, "OneDrive"))
    }

    private suspend fun uploadLargeFileFrom(
        accessToken: String,
        source: File,
        headers: Map<String, String>,
        createOnly: Boolean
    ): FileSourceWriteResult {
        val totalSize = source.length()
        require(totalSize > 0L) { strings.get(R.string.cloud_message_upload_empty, "OneDrive") }
        val sessionPayload = executeJsonRequest(
            relativeUrl = buildUploadSessionRelativeUrl(),
            accessToken = accessToken,
            method = "POST",
            body = json.encodeToString(
                OneDriveUploadSessionRequestDto.serializer(),
                OneDriveUploadSessionRequestDto(
                    item = OneDriveUploadSessionItemDto(
                        conflictBehavior = if (createOnly) "fail" else "replace"
                    )
                )
            ).encodeToByteArray(),
            contentType = "application/json; charset=utf-8",
            headers = headers,
            expectedStatusCodes = setOf(200)
        )
        val session = json.decodeFromString<OneDriveUploadSessionResponseDto>(sessionPayload)
        RandomAccessFile(source, "r").use { input ->
            var offset = 0L
            while (offset < totalSize) {
                val remaining = totalSize - offset
                val chunkSize = minOf(remaining, UPLOAD_CHUNK_SIZE_BYTES.toLong()).toInt()
                val chunk = ByteArray(chunkSize)
                input.seek(offset)
                input.readFully(chunk)
                val endExclusive = offset + chunkSize
                val request = Request.Builder()
                    .url(session.uploadUrl)
                    .header("Content-Range", "bytes $offset-${endExclusive - 1}/$totalSize")
                    .header("Content-Length", chunk.size.toString())
                    .put(chunk.toRequestBody(KEEPASS_KDBX_MIME_TYPE.toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    when (response.code) {
                        200, 201 -> {
                            val item = json.decodeFromString<OneDriveDriveItemDto>(responseBody)
                            return item.toWriteResult()
                        }
                        202 -> offset = endExclusive
                        409 -> throw IOException(strings.get(R.string.cloud_message_immutable_exists))
                        412 -> throw KeePassSourceChangedException(strings.get(R.string.cloud_message_remote_changed))
                        else -> throw IOException(
                            responseBody.ifBlank { strings.get(R.string.cloud_message_upload_http, "OneDrive", response.code) }
                        )
                    }
                }
            }
        }
        throw IOException(strings.get(R.string.cloud_message_upload_unconfirmed, "OneDrive"))
    }

    private suspend fun executeJsonRequest(
        relativeUrl: String,
        accessToken: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String = KEEPASS_KDBX_MIME_TYPE,
        headers: Map<String, String> = emptyMap(),
        expectedStatusCodes: Set<Int> = setOf(200)
    ): String {
        val requestBody = body?.toRequestBody(contentType.toMediaType())
        val requestBuilder = Request.Builder()
            .url(resolveGraphUrl(relativeUrl))
            .header("Authorization", "Bearer $accessToken")
            .method(method, requestBody)
        headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }
        val request = requestBuilder.build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.code !in expectedStatusCodes) {
                if (response.code == 412) {
                    throw KeePassSourceChangedException(strings.get(R.string.cloud_message_remote_changed))
                }
                throw OneDriveHttpException(response.code, responseBody, strings = strings)
            }
            return responseBody
        }
    }

    private fun resolveGraphUrl(relativeOrAbsoluteUrl: String): String {
        return if (relativeOrAbsoluteUrl.startsWith("https://", ignoreCase = true) ||
            relativeOrAbsoluteUrl.startsWith("http://", ignoreCase = true)
        ) {
            relativeOrAbsoluteUrl
        } else {
            "$graphBaseUrl$relativeOrAbsoluteUrl"
        }
    }

    private suspend fun executeBytesRequest(
        relativeUrl: String,
        accessToken: String
    ): ByteArray {
        val request = Request.Builder()
            .url(resolveGraphUrl(relativeUrl))
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OneDriveHttpException(response.code, response.body?.string().orEmpty(), strings = strings)
            }
            return response.body?.bytes() ?: throw IOException(strings.get(R.string.cloud_message_provider_empty_content, "OneDrive"))
        }
    }

    private fun buildItemContentRelativeUrl(): String {
        return itemId?.takeIf { it.isNotBlank() }
            ?.let { "${driveBaseRelativeUrl()}/items/${encodePathSegment(it)}/content" }
            ?: buildPathContentRelativeUrl(normalizedRemotePath)
    }

    private fun buildUploadSessionRelativeUrl(): String {
        return itemId?.takeIf { it.isNotBlank() }
            ?.let { "${driveBaseRelativeUrl()}/items/${encodePathSegment(it)}/createUploadSession" }
            ?: "${driveBaseRelativeUrl()}/root:/${encodePath(normalizedRemotePath)}:/createUploadSession"
    }

    private fun buildChildrenRelativeUrl(path: String): String {
        return if (path.isBlank()) {
            "${driveBaseRelativeUrl()}/root/children"
        } else {
            "${driveBaseRelativeUrl()}/root:/${encodePath(path)}:/children"
        }
    }

    private suspend fun resolveCreateFolderRelativeUrl(parentPath: String): String {
        return if (parentPath.isBlank()) {
            "${driveBaseRelativeUrl()}/root/children"
        } else {
            val parentItem = resolveItemByPath(parentPath)
            "${driveBaseRelativeUrl()}/items/${encodePathSegment(parentItem.id)}/children"
        }
    }

    private fun buildPathMetadataRelativeUrl(path: String): String {
        return "${driveBaseRelativeUrl()}/root:/${encodePath(path)}:"
    }

    private fun buildPathContentRelativeUrl(
        path: String,
        conflictBehavior: String? = null
    ): String {
        val base = "${driveBaseRelativeUrl()}/root:/${encodePath(path)}:/content"
        val behavior = conflictBehavior?.trim()?.takeIf { it.isNotBlank() } ?: return base
        return "$base?@microsoft.graph.conflictBehavior=${encodePathSegment(behavior)}"
    }

    private fun driveBaseRelativeUrl(): String {
        return if (driveId.isNullOrBlank()) {
            "/me/drive"
        } else {
            "/drives/${encodePathSegment(driveId)}"
        }
    }

    private fun requireRemotePath() {
        if (normalizedRemotePath.isBlank()) {
            throw IllegalStateException(strings.get(R.string.cloud_message_path_required))
        }
    }

    private suspend fun pathExists(path: String): Boolean {
        return try {
            resolveItemByPath(path)
            true
        } catch (error: OneDriveHttpException) {
            if (error.statusCode == 404) false else throw error
        }
    }

    private fun encodePath(path: String): String {
        return normalizeRemotePath(path, strings = strings)
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment -> encodePathSegment(segment) }
    }

    private suspend fun accessToken(): String = accessTokenProvider.acquire()
        .takeIf(String::isNotBlank)
        ?: throw IOException(strings.get(R.string.cloud_message_provider_token_missing, "OneDrive"))

    private fun OneDriveDriveItemDto.toStat(): FileSourceStat {
        return FileSourceStat(
            versionToken = eTag ?: cTag,
            etag = eTag,
            lastModified = lastModifiedDateTime?.toEpochMillis(),
            sizeBytes = size,
            remoteId = id,
            driveId = parentReference?.driveId,
            isDirectory = folder != null,
            displayName = name
        )
    }

    private fun OneDriveDriveItemDto.toWriteResult(): FileSourceWriteResult {
        return FileSourceWriteResult(
            versionToken = eTag ?: cTag,
            etag = eTag,
            lastModified = lastModifiedDateTime?.toEpochMillis(),
            remoteId = id,
            driveId = parentReference?.driveId
        )
    }

    companion object {
        private const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
        private const val LARGE_UPLOAD_THRESHOLD_BYTES = 2 * 1024 * 1024
        private const val UPLOAD_CHUNK_SIZE_BYTES = 320 * 1024 * 16
        private val sharedHttpClient = OkHttpClient()

        internal fun encodePathSegment(value: String): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val isUnreserved = unsigned in 'a'.code..'z'.code ||
                    unsigned in 'A'.code..'Z'.code ||
                    unsigned in '0'.code..'9'.code ||
                    unsigned == '-'.code || unsigned == '.'.code ||
                    unsigned == '_'.code || unsigned == '~'.code
                if (isUnreserved) {
                    append(unsigned.toChar())
                } else {
                    append('%')
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0f])
                }
            }
        }

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
            return remotePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                ?.replace(Regex("/+"), "/")
                .orEmpty()
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

        private const val HEX = "0123456789ABCDEF"
    }
}

@Serializable
private data class FolderCreateRequest(
    val name: String,
    val folder: FolderCreateFacet,
    @SerialName("@microsoft.graph.conflictBehavior")
    val conflictBehavior: String
)

@Serializable
private class FolderCreateFacet

@Serializable
private data class RenameItemRequest(
    val name: String
)

private fun String.toEpochMillis(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

object OneDriveKeePassSupport {
    fun createFileSource(
        context: Context,
        source: KeepassRemoteSource
    ): OneDriveKeePassFileSource {
        val strings = AppLocaleStringResolver(context)
        val accountIdentifier = source.tokenRef?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(strings.get(R.string.cloud_message_provider_account_required, "OneDrive"))
        return OneDriveKeePassFileSource(
            context = context,
            accountIdentifier = accountIdentifier,
            driveId = source.driveId,
            itemId = source.itemId,
            remotePath = source.remotePath
        )
    }

    internal fun buildLocalMirrorPaths(sourceId: Long, remotePath: String, strings: StringResolver): KeePassLocalMirrorPaths {
        val fileName = displayNameFromRemotePath(remotePath, strings = strings)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "remote.kdbx" }
        val baseDir = "keepass_remote/onedrive_$sourceId"
        return KeePassLocalMirrorPaths(
            workingCopyPath = "$baseDir/working_$fileName",
            cacheCopyPath = "$baseDir/cache_$fileName"
        )
    }

    internal fun displayNameFromRemotePath(remotePath: String, strings: StringResolver): String {
        val normalized = OneDriveKeePassFileSource.normalizeRemotePath(remotePath, strings = strings)
        return normalized.substringAfterLast('/').ifBlank { "remote.kdbx" }
    }

    fun writeRelativeFile(
        context: Context,
        relativePath: String,
        bytes: ByteArray
    ) {
        WebDavKeePassSupport.writeRelativeFile(context, relativePath, bytes)
    }

    fun deleteRelativeFile(context: Context, relativePath: String?) {
        WebDavKeePassSupport.deleteRelativeFile(context, relativePath)
    }

    fun sha256Hex(bytes: ByteArray): String {
        return WebDavKeePassSupport.sha256Hex(bytes)
    }
}
