package takagi.ru.monica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.sync.SyncErrorKind

class PasswordBatchMoveFailureTest {

    @Test
    fun keepassBatchFailureKeepsRemoteConflictReason() {
        val failure = resolveKeePassBatchTransferFailure(
            failures = mapOf(
                7L to "远端文件已变化，且本地工作副本也有修改，请先处理冲突"
            )
        )

        assertEquals(SyncErrorKind.CONFLICT, failure.kind)
        assertEquals(1, failure.failedCount)
        assertTrue(failure.detail.contains("远端文件已变化"))
    }

    @Test
    fun repeatedBatchReasonsAreCollapsedWithoutLosingFailureCount() {
        val failure = resolveKeePassBatchTransferFailure(
            failures = mapOf(
                1L to "读取或写入 KeePass 文件失败",
                2L to "读取或写入 KeePass 文件失败"
            )
        )

        assertEquals(2, failure.failedCount)
        assertEquals(listOf("读取或写入 KeePass 文件失败"), failure.reasons)
    }
}
