package takagi.ru.monica.attachments.ui

import android.content.Context
import takagi.ru.monica.R
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.normalizeAttachmentFailure
import takagi.ru.monica.bitwarden.service.classifyBitwardenLoginError

/** Shared by attachment preview, upload, and cross-vault transfer failures. */
internal fun attachmentErrorMessage(context: Context, error: Throwable): String {
    val failure = normalizeAttachmentFailure(error)
    val resource = when (failure) {
        is AttachmentError.TooLarge -> R.string.attachment_error_too_large
        AttachmentError.QuotaExceeded -> R.string.attachment_error_quota_exceeded
        AttachmentError.PremiumRequired -> R.string.attachment_error_premium_required
        AttachmentError.Offline -> R.string.attachment_error_offline
        is AttachmentError.NetworkError -> {
            if (failure.httpStatus == 401) return context.getString(R.string.attachment_error_bitwarden_locked)
            failure.networkCause?.let(::classifyBitwardenLoginError)?.let {
                return context.getString(it.messageRes)
            }
            return context.getString(R.string.attachment_error_network, failure.httpStatus?.toString() ?: "-")
        }
        AttachmentError.CryptoError -> R.string.attachment_error_decryption_failed
        AttachmentError.IoError -> R.string.attachment_error_io
        AttachmentError.KdbxLocked -> R.string.attachment_error_kdbx_locked
        AttachmentError.KdbxCapacityExceeded -> R.string.attachment_error_kdbx_capacity
        AttachmentError.BitwardenLocked -> R.string.attachment_error_bitwarden_locked
        AttachmentError.InvalidRemoteData -> R.string.attachment_error_remote_data
        is AttachmentError.UnsupportedEncryption -> return context.getString(
            R.string.attachment_error_unsupported_encryption, failure.encryptionType
        )
        AttachmentError.Unknown -> R.string.attachment_error_unknown
    }
    return if (failure is AttachmentError.TooLarge) {
        val limit = failure.limitBytes
        val size = if (limit >= 1024 * 1024) "${limit / (1024 * 1024)} MB" else "${limit / 1024} KB"
        context.getString(resource, size)
    } else context.getString(resource)
}
