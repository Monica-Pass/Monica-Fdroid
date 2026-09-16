package takagi.ru.monica.utils

import android.app.Activity
import android.content.Context
import takagi.ru.monica.R

data class OneDriveAccountSession(
    val accountId: String,
    val username: String,
    val displayName: String,
    val authority: String? = null,
    val accessToken: String? = null
)

class OneDriveAuthTemporarilyUnavailableException(
    message: String = "OneDrive session is temporarily unavailable.",
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class OneDriveNotSupportedException(
    message: String = "OneDrive is unavailable in the F-Droid build. Use WebDAV."
) : UnsupportedOperationException(message)

class OneDriveAuthManager(context: Context) {
    private val strings = AppLocaleStringResolver(context)

    suspend fun signIn(@Suppress("UNUSED_PARAMETER") activity: Activity): OneDriveAccountSession {
        throw OneDriveNotSupportedException(strings.get(R.string.fdroid_cloud_provider_unavailable, "OneDrive"))
    }

    suspend fun getCachedSession(): OneDriveAccountSession? {
        return null
    }

    suspend fun acquireAccessToken(@Suppress("UNUSED_PARAMETER") accountId: String): OneDriveAccountSession {
        throw OneDriveNotSupportedException(strings.get(R.string.fdroid_cloud_provider_unavailable, "OneDrive"))
    }

    companion object {
        val SCOPES: List<String> = listOf(
            "User.Read",
            "Files.ReadWrite"
        )
    }
}

fun Throwable.isOneDriveAuthTemporarilyUnavailable(): Boolean {
    return generateSequence(this) { it.cause }.any { error ->
        error is OneDriveAuthTemporarilyUnavailableException ||
            error.message.orEmpty().contains("Connection is not available to refresh token", ignoreCase = true) ||
            error.message.orEmpty().contains("power optimization", ignoreCase = true) ||
            error.message.orEmpty().contains("doze mode", ignoreCase = true) ||
            error.message.orEmpty().contains("app is standby", ignoreCase = true)
    }
}

fun Throwable.isOneDriveRedirectHandlerConflict(): Boolean {
    return generateSequence(this) { it.cause }.any { error ->
        val message = error.message.orEmpty()
        message.contains("More than one app is listening for the URL scheme", ignoreCase = true) &&
            message.contains("BrowserTabActivity", ignoreCase = true)
    }
}

internal fun Throwable.toOneDriveUserMessage(strings: StringResolver, fallback: String? = null): String {
    if (this is OneDriveNotSupportedException) {
        return strings.get(R.string.fdroid_cloud_provider_unavailable, "OneDrive")
    }
    if (isOneDriveRedirectHandlerConflict()) {
        return strings.get(R.string.onedrive_error_redirect)
    }
    if (isOneDriveAuthTemporarilyUnavailable()) {
        return strings.get(R.string.onedrive_error_power)
    }
    return message?.takeIf { it.isNotBlank() } ?: fallback ?: strings.get(R.string.onedrive_error_operation)
}
