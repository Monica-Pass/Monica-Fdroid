package takagi.ru.monica.ui.password

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.ui.mdbxPathPendingSyncCount

class MdbxPathSyncStateTest {
    @Test
    fun syncCompletionOverridesStalePendingDiagnostics() {
        val pending = database(MdbxSyncStatus.PENDING_UPLOAD)
        assertEquals(3, pending.mdbxPathPendingSyncCount(3))
        val synced = pending.copy(lastSyncStatus = MdbxSyncStatus.IN_SYNC.name)
        assertEquals(0, synced.mdbxPathPendingSyncCount(3))
        assertEquals(0, database(MdbxSyncStatus.LOCAL_ONLY).mdbxPathPendingSyncCount(1))
    }

    @Test
    fun newChangesAndFailuresCannotBeHiddenByAnOlderZeroCount() {
        for (status in listOf(MdbxSyncStatus.PENDING_UPLOAD, MdbxSyncStatus.FAILED,
            MdbxSyncStatus.REMOTE_CHANGED, MdbxSyncStatus.CONFLICT)) {
            assertEquals(1, database(status).mdbxPathPendingSyncCount(0))
            assertEquals(1, database(status).mdbxPathPendingSyncCount())
            assertEquals(4, database(status).mdbxPathPendingSyncCount(4))
        }
        assertEquals(2, database(MdbxSyncStatus.SYNCING).mdbxPathPendingSyncCount(2))
    }

    private fun database(status: MdbxSyncStatus) = LocalMdbxDatabase(
        name = "Synthetic", filePath = "synthetic.mdbx", lastSyncStatus = status.name,
    )
}
