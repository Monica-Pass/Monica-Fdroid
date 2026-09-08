package takagi.ru.monica.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGhostFilterTest {
    private data class Row(
        val id: Long,
        val group: String,
        val passwordMode: Boolean = true,
        val filterable: Boolean = true,
        val secret: String = "",
    )

    @Test
    fun unrelatedRowsDoNotResolveSecrets() {
        var secretReads = 0
        val rows = listOf(
            Row(id = 1, group = "a", secret = "one"),
            Row(id = 2, group = "b", secret = "two"),
            Row(id = 3, group = "c", secret = "three"),
        )

        val result = findGhostDisplayIds(
            entries = rows,
            idOf = Row::id,
            groupKeyOf = Row::group,
            isPasswordMode = Row::passwordMode,
            shouldFilterGhost = Row::filterable,
            resolveSecret = {
                secretReads += 1
                it.secret
            },
        )

        assertTrue(result.isEmpty())
        assertEquals(0, secretReads)
    }

    @Test
    fun duplicateGroupResolvesOnlyCandidatesAndFiltersBlankGhost() {
        var secretReads = 0
        val rows = listOf(
            Row(id = 1, group = "same", secret = "real-secret"),
            Row(id = 2, group = "same", secret = ""),
            Row(id = 3, group = "other", secret = "never-read"),
        )

        val result = findGhostDisplayIds(
            entries = rows,
            idOf = Row::id,
            groupKeyOf = Row::group,
            isPasswordMode = Row::passwordMode,
            shouldFilterGhost = Row::filterable,
            resolveSecret = {
                secretReads += 1
                it.secret
            },
        )

        assertEquals(setOf(2L), result)
        assertEquals(2, secretReads)
    }

    @Test
    fun blankLocalSiblingIsPreserved() {
        val rows = listOf(
            Row(id = 1, group = "same", secret = "real-secret"),
            Row(id = 2, group = "same", filterable = false, secret = ""),
        )

        val result = findGhostDisplayIds(
            entries = rows,
            idOf = Row::id,
            groupKeyOf = Row::group,
            isPasswordMode = Row::passwordMode,
            shouldFilterGhost = Row::filterable,
            resolveSecret = Row::secret,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun nonPasswordSiblingIsNeverTreatedAsGhostPassword() {
        val rows = listOf(
            Row(id = 1, group = "same", secret = "real-secret"),
            Row(id = 2, group = "same", passwordMode = false, secret = ""),
        )

        val result = findGhostDisplayIds(
            entries = rows,
            idOf = Row::id,
            groupKeyOf = Row::group,
            isPasswordMode = Row::passwordMode,
            shouldFilterGhost = Row::filterable,
            resolveSecret = Row::secret,
        )

        assertTrue(result.isEmpty())
    }
}
