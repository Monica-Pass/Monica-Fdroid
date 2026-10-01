package takagi.ru.monica.webdav

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavTimeoutClassificationTest {
    @Test fun outerCallTimeoutSurvivesCanceledCause() {
        val timeout = InterruptedIOException("timeout").apply { initCause(IOException("Canceled")) }
        assertEquals(WebDavErrorKind.Timeout, WebDavErrorClassifier.classify(Exception("upload", timeout)).kind)
    }
    @Test fun cancellationAndThreadInterruptionAreNotTimeouts() {
        assertEquals(WebDavErrorKind.Unknown, WebDavErrorClassifier.classify(IOException("Canceled")).kind)
        assertEquals(WebDavErrorKind.Unknown, WebDavErrorClassifier.classify(InterruptedIOException("interrupted")).kind)
    }
    @Test fun socketTimeoutWithNestedIoRetainsItsMeaning() {
        assertEquals(WebDavErrorKind.Timeout, WebDavErrorClassifier.classify(
            SocketTimeoutException().apply { initCause(IOException("closed")) }).kind)
    }
}
