package takagi.ru.monica.attachments.storage

import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException

internal class AttachmentKeyUnavailableException(
    val reason: Reason,
    cause: Throwable? = null,
) : GeneralSecurityException("Attachment key unavailable: ${reason.name}", cause) {
    enum class Reason { MISSING, UNWRAP_FAILED }
}

/** Keep attachment-key failures separate from blob reads and retain their cause for diagnosis. */
internal fun readAttachmentKey(wrapped: String?, unwrap: (String) -> ByteArray): ByteArray {
    val value = wrapped?.takeIf { it.isNotBlank() }
        ?: throw AttachmentKeyUnavailableException(AttachmentKeyUnavailableException.Reason.MISSING)
    return try {
        unwrap(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw AttachmentKeyUnavailableException(AttachmentKeyUnavailableException.Reason.UNWRAP_FAILED, error)
    }
}
