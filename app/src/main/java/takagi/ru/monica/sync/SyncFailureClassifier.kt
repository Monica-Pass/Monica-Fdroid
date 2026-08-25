package takagi.ru.monica.sync

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

internal fun classifySyncFailure(error: Throwable): SyncError {
    val messages = generateSequence(error) { current ->
        current.cause?.takeUnless { it === current }
    }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .toList()
    val combined = messages.joinToString(" | ").lowercase(Locale.ROOT)
    val kind = when {
        combined.contains("远端文件已变化") ||
            combined.contains("remote conflict") ||
            combined.contains("precondition failed") ||
            combined.contains("http 412") -> SyncErrorKind.CONFLICT

        combined.contains("http 401") ||
            combined.contains("unauthorized") ||
            combined.contains("invalid_grant") ||
            combined.contains("token expired") ||
            combined.contains("登录已失效") -> SyncErrorKind.AUTH_REQUIRED

        error.hasCause<UnknownHostException>() ||
            error.hasCause<SocketTimeoutException>() ||
            error.hasCause<ConnectException>() ||
            combined.contains("unable to resolve host") ||
            combined.contains("failed to connect") ||
            combined.contains("network is unreachable") ||
            combined.contains("timeout") -> SyncErrorKind.NETWORK_UNAVAILABLE

        combined.contains("permission denied") ||
            combined.contains("http 403") ||
            combined.contains("forbidden") ||
            combined.contains("缺少") && combined.contains("权限") -> SyncErrorKind.PERMISSION_DENIED

        combined.contains("database is locked") ||
            combined.contains("数据库未解锁") -> SyncErrorKind.TARGET_LOCKED

        combined.contains("http 429") || combined.contains("rate limit") -> SyncErrorKind.RATE_LIMITED
        combined.contains("http 5") || combined.contains("service unavailable") -> SyncErrorKind.REMOTE_UNAVAILABLE
        else -> SyncErrorKind.UNEXPECTED
    }
    return SyncError(
        kind = kind,
        redactedMessage = messages.firstOrNull() ?: error.javaClass.simpleName,
        retryable = kind in setOf(
            SyncErrorKind.NETWORK_UNAVAILABLE,
            SyncErrorKind.RATE_LIMITED,
            SyncErrorKind.REMOTE_UNAVAILABLE,
        )
    )
}

internal fun syncExecutionFailure(
    error: Throwable,
    finishedAtMillis: Long,
): SyncExecutionResult {
    val classified = classifySyncFailure(error)
    return if (classified.kind == SyncErrorKind.CONFLICT) {
        SyncExecutionResult.Conflict(
            finishedAtMillis = finishedAtMillis,
            error = classified,
        )
    } else {
        SyncExecutionResult.Failed(
            finishedAtMillis = finishedAtMillis,
            error = classified,
        )
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    return generateSequence(this) { current ->
        current.cause?.takeUnless { it === current }
    }.any { it is T }
}
