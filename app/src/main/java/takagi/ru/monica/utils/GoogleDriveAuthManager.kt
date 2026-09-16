package takagi.ru.monica.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import takagi.ru.monica.R

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
    message: String = "Google Drive is unavailable in the F-Droid build. Use WebDAV."
) : UnsupportedOperationException(message)

class GoogleDriveAuthManager(context: Context) {
    private val strings = AppLocaleStringResolver(context)

    suspend fun beginAuthorization(@Suppress("UNUSED_PARAMETER") expectedAccountId: String? = null): GoogleDriveAuthorizationStep {
        throw GoogleDriveNotSupportedException(strings.get(R.string.fdroid_cloud_provider_unavailable, "Google Drive"))
    }

    suspend fun completeAuthorization(@Suppress("UNUSED_PARAMETER") data: Intent?, @Suppress("UNUSED_PARAMETER") expectedAccountId: String? = null): GoogleDriveAccountSession {
        throw GoogleDriveNotSupportedException(strings.get(R.string.fdroid_cloud_provider_unavailable, "Google Drive"))
    }

    suspend fun getCachedSession(@Suppress("UNUSED_PARAMETER") expectedAccountId: String? = null): GoogleDriveAccountSession? {
        return null
    }

    suspend fun acquireAccessToken(@Suppress("UNUSED_PARAMETER") accountId: String): GoogleDriveAccountSession {
        throw GoogleDriveNotSupportedException(strings.get(R.string.fdroid_cloud_provider_unavailable, "Google Drive"))
    }

    suspend fun revokeAccess(@Suppress("UNUSED_PARAMETER") accountId: String) {
        // F-Droid build: no GMS authorization to revoke; keep disconnect flows no-op.
    }

    suspend fun clearAccessToken(@Suppress("UNUSED_PARAMETER") accessToken: String) {
        // F-Droid build: no GMS token cache to clear; keep cleanup flows no-op.
    }
}
