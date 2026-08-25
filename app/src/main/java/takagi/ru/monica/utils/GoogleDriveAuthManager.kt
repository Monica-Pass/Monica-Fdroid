package takagi.ru.monica.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

data class GoogleDriveAccountSession(
    val accountId: String,
    val username: String,
    val displayName: String,
    val accessToken: String? = null
)

sealed interface GoogleDriveAuthorizationStep {
    data class Authorized(val session: GoogleDriveAccountSession) : GoogleDriveAuthorizationStep
    data class ResolutionRequired(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationStep
}

class GoogleDriveNotSupportedException(
    message: String = "此构建（F-Droid 版）不包含 Google Drive 支持，请使用 WebDAV 同步。"
) : UnsupportedOperationException(message)

class GoogleDriveAuthManager(@Suppress("UNUSED_PARAMETER") context: Context) {

    suspend fun beginAuthorization(@Suppress("UNUSED_PARAMETER") expectedAccountId: String? = null): GoogleDriveAuthorizationStep {
        throw GoogleDriveNotSupportedException()
    }

    suspend fun completeAuthorization(@Suppress("UNUSED_PARAMETER") data: Intent?, @Suppress("UNUSED_PARAMETER") expectedAccountId: String? = null): GoogleDriveAccountSession {
        throw GoogleDriveNotSupportedException()
    }

    suspend fun getCachedSession(@Suppress("UNUSED_PARAMETER") expectedAccountId: String? = null): GoogleDriveAccountSession? {
        return null
    }

    suspend fun acquireAccessToken(@Suppress("UNUSED_PARAMETER") accountId: String): GoogleDriveAccountSession {
        throw GoogleDriveNotSupportedException()
    }

    suspend fun revokeAccess(@Suppress("UNUSED_PARAMETER") accountId: String) {
        // F-Droid build: no GMS authorization to revoke; keep disconnect flows no-op.
    }

    suspend fun clearAccessToken(@Suppress("UNUSED_PARAMETER") accessToken: String) {
        // F-Droid build: no GMS token cache to clear; keep cleanup flows no-op.
    }
}
