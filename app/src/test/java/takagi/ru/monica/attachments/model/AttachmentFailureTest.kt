package takagi.ru.monica.attachments.model

import java.io.IOException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto

class AttachmentFailureTest {
    @Test fun cryptoFailureIsNotAStoragePermissionError() {
        assertSame(AttachmentError.CryptoError, normalizeAttachmentFailure(GeneralSecurityException("synthetic MAC failure")))
        assertSame(AttachmentError.CryptoError, normalizeAttachmentFailure(IOException(GeneralSecurityException())))
    }

    @Test fun realLocalIoFailureRemainsAnIoFailure() {
        assertSame(AttachmentError.IoError, normalizeAttachmentFailure(IOException("synthetic disk failure")))
    }

    @Test fun certificateFailureRetainsItsCauseForLocalizedNetworkDetails() {
        val ssl = SSLHandshakeException("synthetic untrusted certificate")
        val failure = normalizeAttachmentFailure(ssl) as AttachmentError.NetworkError
        assertSame(ssl, failure.networkCause)
    }

    @Test fun unsupportedEncryptionIsExplicit() {
        assertEquals(AttachmentError.UnsupportedEncryption(7), normalizeAttachmentFailure(BitwardenAttachmentCrypto.UnsupportedFormatException(7)))
    }

    @Test fun cancellationIsNotAnAttachmentFailure() {
        val cancellation = CancellationException("synthetic cancellation")
        assertSame(cancellation, assertThrows(CancellationException::class.java) { normalizeAttachmentFailure(cancellation) })
    }
}
