package takagi.ru.monica.attachments.storage

import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentKeyAccessTest {
    @Test fun unwrapFailureRetainsTheActualCryptoCause() {
        val cause = AEADBadTagException("synthetic wrong wrapping key")
        val error = assertThrows(AttachmentKeyUnavailableException::class.java) {
            readAttachmentKey("test-wrapped-key") { throw cause }
        }
        assertEquals(AttachmentKeyUnavailableException.Reason.UNWRAP_FAILED, error.reason)
        assertSame(cause, error.cause)
    }

    @Test fun missingKeyIsDistinctFromWrongKey() {
        for (wrapped in listOf(null, "", "  ")) {
            val error = assertThrows(AttachmentKeyUnavailableException::class.java) {
                readAttachmentKey(wrapped) { error("Must not try to unwrap a missing key") }
            }
            assertEquals(AttachmentKeyUnavailableException.Reason.MISSING, error.reason)
        }
    }

    @Test fun aValidKeyIsReturnedToTheDecryptionCaller() {
        val key = ByteArray(32) { it.toByte() }
        assertSame(key, readAttachmentKey("test-wrapped-key") { key })
    }

    @Test fun cancellationIsNotReportedAsCorruptData() {
        val cancelled = CancellationException("synthetic cancellation")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            readAttachmentKey("test-wrapped-key") { throw cancelled }
        })
    }

    @Test fun vmErrorsAreNotReportedAsCryptoFailures() {
        val failure = OutOfMemoryError("synthetic failure, no allocation")
        assertSame(failure, assertThrows(OutOfMemoryError::class.java) {
            readAttachmentKey("test-wrapped-key") { throw failure }
        })
    }
}
