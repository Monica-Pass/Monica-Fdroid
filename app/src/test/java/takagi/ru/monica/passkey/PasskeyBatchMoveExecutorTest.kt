package takagi.ru.monica.passkey

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasskeyEntry

class PasskeyBatchMoveExecutorTest {
    private val entries = (1L..2L).map { PasskeyEntry(id = it, credentialId = "credential-$it",
        rpId = "example.invalid", rpName = "Example", userId = "user", userName = "User",
        userDisplayName = "User", publicKey = "public-$it", privateKeyAlias = "private-$it", signCount = 7) }

    @Test fun failedTargetNeverQueuesSourceDeletion() = runBlocking {
        val queued = mutableListOf<Long>()
        val completed = mutableListOf<Result<Unit>>()
        PasskeyBatchMoveExecutor.execute(entries,
            persistTarget = { Result.failure(IllegalStateException("disk full")) },
            deleteSource = { queued += it.id; Result.success(Unit) },
            onCompleted = { _, result -> completed += result })
        assertTrue(queued.isEmpty())
        assertEquals(2, completed.size)
        assertTrue(completed.all { it.isFailure })
    }

    @Test fun successfulTargetPrecedesAllSourceDeletions() = runBlocking {
        val events = mutableListOf<String>()
        PasskeyBatchMoveExecutor.execute(entries,
            persistTarget = { assertEquals(listOf(1L, 2L), it); events += "saved"; Result.success(Unit) },
            deleteSource = { assertEquals(7L, it.signCount); events += "delete-${it.id}"; Result.success(Unit) },
            onCompleted = { _, result -> assertTrue(result.isSuccess) })
        assertEquals(listOf("saved", "delete-1", "delete-2"), events)
    }

    @Test fun sourceCleanupFailureDoesNotUndoSavedDestinationOrSkipOtherSources() = runBlocking {
        var writes = 0
        val completed = mutableListOf<Boolean>()
        PasskeyBatchMoveExecutor.execute(entries,
            persistTarget = { writes++; Result.success(Unit) },
            deleteSource = { if (it.id == 1L) Result.failure(IllegalStateException("offline")) else Result.success(Unit) },
            onCompleted = { _, result -> completed += result.isSuccess })
        assertEquals(1, writes)
        assertEquals(listOf(false, true), completed)
    }

    @Test fun cancellationNeverContinuesWithSourceDeletion() {
        var queued = 0
        try {
            runBlocking {
                PasskeyBatchMoveExecutor.execute(entries,
                    persistTarget = { Result.failure(CancellationException("cancelled")) },
                    deleteSource = { queued++; Result.success(Unit) },
                    onCompleted = { _, _ -> fail("Cancellation must propagate") })
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, queued)
        }
    }
}
