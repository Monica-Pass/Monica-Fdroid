package takagi.ru.monica.ui.screens

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassNativeManagerLayoutTest {

    @Test
    fun `selection toggle adds and removes one entry without touching other entries`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertEquals(
            setOf(first, second),
            toggleNativeManagerEntrySelection(setOf(first), second),
        )
        assertEquals(
            setOf(second),
            toggleNativeManagerEntrySelection(setOf(first, second), first),
        )
    }

    @Test
    fun `select all only changes entries visible in the current folder`() {
        val visible = setOf(UUID.randomUUID(), UUID.randomUUID())
        val hidden = UUID.randomUUID()

        val selected = toggleNativeManagerSelectAll(
            selected = setOf(hidden),
            visible = visible,
        )
        assertTrue(selected.containsAll(visible))
        assertTrue(hidden in selected)

        val cleared = toggleNativeManagerSelectAll(selected, visible)
        assertEquals(setOf(hidden), cleared)
    }

    @Test
    fun `empty visible list leaves selection unchanged`() {
        val selected = setOf(UUID.randomUUID())

        assertEquals(selected, toggleNativeManagerSelectAll(selected, emptySet()))
    }

    @Test
    fun `search field is visible while expanded or when query is present`() {
        assertTrue(shouldShowNativeManagerSearch(searchExpanded = true, query = ""))
        assertTrue(shouldShowNativeManagerSearch(searchExpanded = false, query = "mail"))
        assertFalse(shouldShowNativeManagerSearch(searchExpanded = false, query = "  "))
    }

    @Test
    fun `summary counts folders and entries independently`() {
        val summary = NativeManagerListSummary(folderCount = 3, entryCount = 8)

        assertEquals(11, summary.totalCount)
        assertEquals("3 folders · 8 entries", summary.compactLabel("folders", "entries"))
    }
}
