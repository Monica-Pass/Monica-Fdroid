package takagi.ru.monica.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MdbxSyncStateTest {

    @Test
    fun stateSurvivesStoreRestartAndConcurrentUpdatesAreSerialized() = runBlocking {
        val dao = FakeDao()
        val first = MdbxSyncStateStore(dao)
        val checkpoint = MdbxSyncCheckpointState("commit-token", "delta-token")
        first.write(
            databaseId = 7L,
            state = MdbxSyncStateSnapshot(
                vaultId = "vault-7",
                generationId = "generation-a",
                bootstrapCheckpoint = checkpoint,
                exportCheckpoint = checkpoint,
                remoteStreams = listOf(
                    MdbxRemoteStreamState(
                        streamId = "device-a",
                        generationId = "generation-a",
                        nextSequence = 3,
                        checkpoint = checkpoint
                    )
                )
            )
        )

        val second = MdbxSyncStateStore(dao)
        assertEquals("vault-7", second.read(7L).vaultId)
        assertEquals(3L, second.read(7L).remoteStreams.single().nextSequence)

        (0 until 20).map { index ->
            async {
                second.update(7L) { state ->
                    state.copy(
                        blobTransfers = state.blobTransfers + MdbxBlobTransferState(
                            blobId = index.toString().padStart(64, '0'),
                            totalSize = index.toLong() + 1,
                            ownerId = "owner-$index",
                            nextOffset = 0,
                            direction = "upload"
                        )
                    )
                }
            }
        }.awaitAll()

        val restored = MdbxSyncStateStore(dao).read(7L)
        assertEquals(20, restored.blobTransfers.size)
        assertNotNull(restored.bootstrapCheckpoint)
    }

    private class FakeDao : MdbxSyncStateDao {
        private var value: MdbxSyncStateEntity? = null

        override suspend fun get(databaseId: Long): MdbxSyncStateEntity? =
            value?.takeIf { it.databaseId == databaseId }

        override suspend fun upsert(state: MdbxSyncStateEntity) {
            value = state
        }

        override suspend fun delete(databaseId: Long) {
            if (value?.databaseId == databaseId) value = null
        }
    }
}
