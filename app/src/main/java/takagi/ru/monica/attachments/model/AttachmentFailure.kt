package takagi.ru.monica.attachments.model

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto

/** Keep crypto/session failures distinct from filesystem permissions, including wrapped IO errors. */
internal fun normalizeAttachmentFailure(error: Throwable): AttachmentError {
    var current: Throwable? = error
    repeat(12) {
        when (val cause = current) {
            null -> return@repeat
            is CancellationException -> throw cause
            is AttachmentError -> return cause
            is BitwardenAttachmentCrypto.UnsupportedFormatException ->
                return AttachmentError.UnsupportedEncryption(cause.encryptionType)
            is GeneralSecurityException -> return AttachmentError.CryptoError
            is HttpException -> return AttachmentError.NetworkError(cause.code(), cause)
            is SSLException, is UnknownHostException, is SocketException, is SocketTimeoutException ->
                return AttachmentError.NetworkError(null, cause)
        }
        current = current?.cause?.takeUnless { it === current }
    }
    return if (error is IOException || error is SecurityException) AttachmentError.IoError else AttachmentError.Unknown
}
