package takagi.ru.monica.sync

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFailureClassifierTest {

    @Test
    fun remoteAndLocalKeePassChangesAreClassifiedAsConflict() {
        val error = IllegalStateException(
            "远端文件已变化，且本地工作副本也有修改，请先处理冲突"
        )

        val classified = classifySyncFailure(error)

        assertEquals(SyncErrorKind.CONFLICT, classified.kind)
        assertFalse(classified.retryable)
    }

    @Test
    fun networkTimeoutIsClassifiedAsRetryableNetworkFailure() {
        val classified = classifySyncFailure(SocketTimeoutException("timeout"))

        assertEquals(SyncErrorKind.NETWORK_UNAVAILABLE, classified.kind)
        assertTrue(classified.retryable)
    }

    @Test
    fun unauthorizedOneDriveResponseRequestsAuthentication() {
        val classified = classifySyncFailure(IOException("OneDrive HTTP 401 unauthorized"))

        assertEquals(SyncErrorKind.AUTH_REQUIRED, classified.kind)
        assertFalse(classified.retryable)
    }

    @Test
    fun conflictExceptionProducesConflictExecutionResult() {
        val result = syncExecutionFailure(
            error = IllegalStateException("远端文件已变化，请先重新同步"),
            finishedAtMillis = 12L
        )

        assertTrue(result is SyncExecutionResult.Conflict)
        assertEquals(
            SyncErrorKind.CONFLICT,
            (result as SyncExecutionResult.Conflict).error.kind
        )
    }
}
