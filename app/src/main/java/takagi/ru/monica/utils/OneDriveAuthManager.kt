package takagi.ru.monica.utils

import android.app.Activity
import android.content.Context

data class OneDriveAccountSession(
    val accountId: String,
    val username: String,
    val displayName: String,
    val authority: String? = null,
    val accessToken: String? = null
)

class OneDriveAuthTemporarilyUnavailableException(
    message: String = "OneDrive 暂时无法刷新登录状态。请关闭系统电池优化，或点亮屏幕并重新打开 Monica 后再试。",
    cause: Throwable? = null
) : IllegalStateException(message, cause)

const val ONEDRIVE_REDIRECT_CONFLICT_USER_MESSAGE: String =
    "检测到旧版 Monica Steam 占用了 OneDrive 登录回调。请更新 Monica Steam 后重试；数据库文件本身没有损坏。"

class OneDriveNotSupportedException(
    message: String = "此构建（F-Droid 版）不包含 OneDrive 支持，请使用 WebDAV 同步。"
) : UnsupportedOperationException(message)

class OneDriveAuthManager(@Suppress("UNUSED_PARAMETER") context: Context) {

    suspend fun signIn(@Suppress("UNUSED_PARAMETER") activity: Activity): OneDriveAccountSession {
        throw OneDriveNotSupportedException()
    }

    suspend fun getCachedSession(): OneDriveAccountSession? {
        return null
    }

    suspend fun acquireAccessToken(@Suppress("UNUSED_PARAMETER") accountId: String): OneDriveAccountSession {
        throw OneDriveNotSupportedException()
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

fun Throwable.toOneDriveUserMessage(fallback: String = "OneDrive 操作失败"): String {
    if (this is OneDriveNotSupportedException) {
        return message ?: fallback
    }
    if (isOneDriveRedirectHandlerConflict()) {
        return ONEDRIVE_REDIRECT_CONFLICT_USER_MESSAGE
    }
    if (isOneDriveAuthTemporarilyUnavailable()) {
        return "OneDrive 暂时无法刷新登录状态。请关闭系统电池优化，或点亮屏幕并重新打开 Monica 后再试。"
    }
    return message?.takeIf { it.isNotBlank() } ?: fallback
}
