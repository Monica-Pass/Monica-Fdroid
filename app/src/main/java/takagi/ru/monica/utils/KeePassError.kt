package takagi.ru.monica.utils

import takagi.ru.monica.R

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import takagi.ru.monica.keepass.KeePassDatabaseReadOnlyException

enum class KeePassErrorCode {
    LEGACY_KDB_UNSUPPORTED,
    FORMAT_UNSUPPORTED,
    INVALID_CREDENTIAL,
    KEY_FILE_UNAVAILABLE,
    DATABASE_READ_ONLY,
    ONEDRIVE_REDIRECT_CONFLICT,
    URI_PERMISSION_DENIED,
    KDF_MEMORY_INSUFFICIENT,
    IO_READ_WRITE_FAILED
}

class KeePassOperationException(
    val code: KeePassErrorCode,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

internal fun Throwable.toKeePassOperationException(strings: StringResolver): KeePassOperationException {
    if (this is KeePassOperationException) return this

    val root = rootCause()
    val lowerMessage = (root.message ?: message ?: "").lowercase(Locale.ROOT)

    fun wrap(code: KeePassErrorCode, userMessage: String): KeePassOperationException {
        return KeePassOperationException(code = code, message = userMessage, cause = this)
    }

    if (root is KeePassDatabaseReadOnlyException) {
        return wrap(
            code = KeePassErrorCode.DATABASE_READ_ONLY,
            userMessage = strings.get(R.string.keepass_error_read_only)
        )
    }

    if (isOneDriveRedirectHandlerConflict()) {
        return wrap(
            code = KeePassErrorCode.ONEDRIVE_REDIRECT_CONFLICT,
            userMessage = strings.get(R.string.onedrive_error_redirect)
        )
    }

    if (root is SecurityException || lowerMessage.contains("permission denied") || lowerMessage.contains("eacces")) {
        return wrap(
            code = KeePassErrorCode.URI_PERMISSION_DENIED,
            userMessage = strings.get(R.string.keepass_error_write_permission)
        )
    }

    if (root is OutOfMemoryError ||
        (lowerMessage.contains("argon2") && lowerMessage.contains("memory")) ||
        lowerMessage.contains("outofmemory")
    ) {
        return wrap(
            code = KeePassErrorCode.KDF_MEMORY_INSUFFICIENT,
            userMessage = strings.get(R.string.keepass_error_memory)
        )
    }

    if (root is CryptoError.InvalidKey ||
        lowerMessage.contains("wrong key used for decryption") ||
        lowerMessage.contains("invalid credentials")
    ) {
        return wrap(
            code = KeePassErrorCode.INVALID_CREDENTIAL,
            userMessage = strings.get(R.string.keepass_error_credentials)
        )
    }

    if (lowerMessage.contains("legacy kdb") ||
        lowerMessage.contains(".kdb (v1") ||
        lowerMessage.contains("keepass 1.x")
    ) {
        return wrap(
            code = KeePassErrorCode.LEGACY_KDB_UNSUPPORTED,
            userMessage = strings.get(R.string.keepass_error_legacy)
        )
    }

    if (root is FormatError.UnsupportedVersion ||
        root is FormatError.UnknownFormat ||
        root is FormatError.InvalidHeader ||
        root is FormatError.InvalidContent ||
        root is FormatError.InvalidXml ||
        root is FormatError.FailedCompression ||
        lowerMessage.contains("unsupported cipher id") ||
        lowerMessage.contains("unsupported header field") ||
        lowerMessage.contains("unknown format")
    ) {
        return wrap(
            code = KeePassErrorCode.FORMAT_UNSUPPORTED,
            userMessage = strings.get(R.string.keepass_error_format)
        )
    }

    if (root is FileNotFoundException || root is IOException) {
        return wrap(
            code = KeePassErrorCode.IO_READ_WRITE_FAILED,
            userMessage = strings.get(R.string.keepass_error_io)
        )
    }

    return wrap(
        code = KeePassErrorCode.IO_READ_WRITE_FAILED,
        userMessage = root.message?.takeIf { it.isNotBlank() } ?: strings.get(R.string.keepass_error_operation)
    )
}

fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    while (current.cause != null && current.cause !== current) {
        current = current.cause!!
    }
    return current
}
