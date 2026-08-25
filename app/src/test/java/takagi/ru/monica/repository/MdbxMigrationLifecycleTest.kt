package takagi.ru.monica.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxMigrationLifecycleTest {
    @Test
    fun verifiedTargetIsReturnedWithoutCleanup() = runBlocking {
        val events = mutableListOf<String>()

        val result = MdbxMigrationLifecycle.withIsolatedTarget(
            createTarget = {
                events += "create"
                "target"
            },
            cleanupTarget = { events += "cleanup:$it" },
            migrateAndVerify = {
                events += "verify:$it"
                42
            }
        )

        assertEquals(42, result)
        assertEquals(listOf("create", "verify:target"), events)
    }

    @Test
    fun migrationFailureCleansTargetAndPreservesOriginalError() = runBlocking {
        val expected = IllegalStateException("copy failed")
        val events = mutableListOf<String>()

        val thrown = runCatching {
            MdbxMigrationLifecycle.withIsolatedTarget(
                createTarget = { "target" },
                cleanupTarget = { events += "cleanup:$it" },
                migrateAndVerify = { throw expected }
            )
        }.exceptionOrNull()

        assertSame(expected, thrown)
        assertEquals(listOf("cleanup:target"), events)
    }

    @Test
    fun cancellationAlsoCompletesSuspendingCleanup() = runBlocking {
        val targetCreated = CompletableDeferred<Unit>()
        val cleanupCompleted = CompletableDeferred<Unit>()
        val migration = launch {
            MdbxMigrationLifecycle.withIsolatedTarget(
                createTarget = {
                    targetCreated.complete(Unit)
                    "target"
                },
                cleanupTarget = {
                    delay(1)
                    cleanupCompleted.complete(Unit)
                },
                migrateAndVerify = { awaitCancellation() }
            )
        }

        targetCreated.await()
        migration.cancelAndJoin()

        assertTrue(cleanupCompleted.isCompleted)
    }

    @Test
    fun cleanupFailureIsSuppressedOnMigrationError() = runBlocking {
        val migrationError = IllegalStateException("copy failed")
        val cleanupError = IllegalStateException("cleanup failed")

        val thrown = runCatching {
            MdbxMigrationLifecycle.withIsolatedTarget(
                createTarget = { "target" },
                cleanupTarget = { throw cleanupError },
                migrateAndVerify = { throw migrationError }
            )
        }.exceptionOrNull()

        assertSame(migrationError, thrown)
        val suppressed = thrown?.suppressed?.single()
        assertEquals(cleanupError::class, suppressed?.let { it::class })
        assertEquals(cleanupError.message, suppressed?.message)
    }
}
