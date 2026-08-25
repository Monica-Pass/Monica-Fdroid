package takagi.ru.monica.utils

import android.content.Context
import java.io.File
import takagi.ru.monica.webdav.WebDavErrorClassifier
import takagi.ru.monica.webdav.WebDavErrorKind

class WebDavMdbxRemoteTransport(
    serverUrl: String,
    username: String,
    password: String
) : MdbxRemoteTransport {
    private val source = WebDavMdbxFileSource(serverUrl, username, password)

    override suspend fun testConnection() {
        source.testConnection().getOrThrow()
    }

    override suspend fun stat(path: String): MdbxRemoteObject? =
        source.statPath(MdbxRemoteSyncPaths.normalizePath(path))?.toRemoteObject()

    override suspend fun list(path: String?): List<MdbxRemoteObject> =
        try {
            source.listDirectory(path?.let(MdbxRemoteSyncPaths::normalizePath))
        } catch (error: Throwable) {
            if (WebDavErrorClassifier.classify(error).kind == WebDavErrorKind.NotFound) {
                emptyList()
            } else {
                throw error
            }
        }
            .map(FileSourceEntry::toRemoteObject)

    override suspend fun ensureDirectory(path: String) {
        source.ensureDirectoryPath(MdbxRemoteSyncPaths.normalizePath(path))
    }

    override suspend fun readTo(path: String, destination: File) {
        source.readFileTo(MdbxRemoteSyncPaths.normalizePath(path), destination)
    }

    override suspend fun writeFrom(
        path: String,
        source: File,
        mode: MdbxRemoteWriteMode,
        expectedVersion: String?
    ): MdbxRemoteObject = this.source
        .writeFileFrom(MdbxRemoteSyncPaths.normalizePath(path), source, mode, expectedVersion)
        .toRemoteObject()
}

class OneDriveMdbxRemoteTransport(
    context: Context,
    accountId: String
) : MdbxRemoteTransport {
    private val source = OneDriveMdbxFileSource(context.applicationContext, accountId)

    override suspend fun testConnection() {
        source.testConnection().getOrThrow()
    }

    override suspend fun stat(path: String): MdbxRemoteObject? =
        source.statPath(MdbxRemoteSyncPaths.normalizePath(path))?.let { stat ->
            MdbxRemoteObject(
                path = MdbxRemoteSyncPaths.normalizePath(path),
                isDirectory = stat.isDirectory,
                sizeBytes = stat.sizeBytes,
                versionToken = stat.versionToken ?: stat.etag,
                lastModified = stat.lastModified
            )
        }

    override suspend fun list(path: String?): List<MdbxRemoteObject> =
        try {
            source.listDirectoryAll(path?.let(MdbxRemoteSyncPaths::normalizePath))
        } catch (error: Throwable) {
            if (error is OneDriveHttpException && error.statusCode == 404) {
                emptyList()
            } else {
                throw error
            }
        }
            .map(FileSourceEntry::toRemoteObject)

    override suspend fun ensureDirectory(path: String) {
        source.ensureDirectoryPath(MdbxRemoteSyncPaths.normalizePath(path))
    }

    override suspend fun readTo(path: String, destination: File) {
        source.readFileTo(MdbxRemoteSyncPaths.normalizePath(path), destination)
    }

    override suspend fun writeFrom(
        path: String,
        source: File,
        mode: MdbxRemoteWriteMode,
        expectedVersion: String?
    ): MdbxRemoteObject {
        val result = this.source.writeFileFrom(
            MdbxRemoteSyncPaths.normalizePath(path),
            source,
            mode,
            expectedVersion
        )
        return MdbxRemoteObject(
            path = MdbxRemoteSyncPaths.normalizePath(path),
            isDirectory = false,
            sizeBytes = source.length(),
            versionToken = result.versionToken ?: result.etag,
            lastModified = result.lastModified
        )
    }
}

private fun FileSourceEntry.toRemoteObject(): MdbxRemoteObject = MdbxRemoteObject(
    path = path,
    isDirectory = isDirectory,
    sizeBytes = sizeBytes,
    versionToken = versionToken,
    lastModified = lastModified
)
