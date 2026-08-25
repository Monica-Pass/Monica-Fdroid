package takagi.ru.monica.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxMutationConsistencyTest {
    @Test
    fun roomFirstFailureRollsBackMirrorThenRoom() = runBlocking {
        val events = mutableListOf<String>()
        val expected = IllegalStateException("mirror failed")

        val thrown = runCatching {
            commitRoomThenMirror(
                roomCommit = { events += "room-commit"; 42L },
                mirrorCommit = { events += "mirror-commit"; throw expected },
                rollbackRoom = { events += "room-rollback" },
                rollbackMirror = { events += "mirror-rollback" }
            )
        }.exceptionOrNull()

        assertSame(expected, thrown)
        assertEquals(
            listOf("room-commit", "mirror-commit", "mirror-rollback", "room-rollback"),
            events
        )
    }

    @Test
    fun mirrorFirstRoomFailureRestoresMirror() = runBlocking {
        var mirrored = false
        var roomChanged = false
        val expected = IllegalStateException("room failed")

        val thrown = runCatching {
            commitMirrorThenRoom(
                mirrorCommit = { mirrored = true },
                roomCommit = { roomChanged = true; throw expected },
                rollbackMirror = { mirrored = false }
            )
        }.exceptionOrNull()

        assertSame(expected, thrown)
        assertFalse(mirrored)
        assertTrue(roomChanged)
    }

    @Test
    fun rollbackFailureIsSuppressedWithoutMaskingPrimaryFailure() = runBlocking {
        val expected = IllegalStateException("commit failed")
        val rollbackFailure = IllegalArgumentException("rollback failed")

        val thrown = runCatching {
            commitMirrorThenRoom(
                mirrorCommit = { throw expected },
                roomCommit = { Unit },
                rollbackMirror = { throw rollbackFailure }
            )
        }.exceptionOrNull()

        assertSame(expected, thrown)
        assertEquals(listOf(rollbackFailure), thrown?.suppressedExceptions)
    }
}
