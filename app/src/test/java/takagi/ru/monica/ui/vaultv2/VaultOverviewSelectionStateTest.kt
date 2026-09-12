package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.data.VaultOverviewModule

class VaultOverviewSelectionStateTest {
    @Test fun theSameEntryInTwoModulesKeepsDistinctActionContexts() {
        val selection = VaultOverviewSelectionState()
        selection.toggle(VaultOverviewModule.ITEMS, "password:1")
        selection.toggle(VaultOverviewModule.ITEMS, "password:2")
        assertFalse(selection.isSelected(VaultOverviewModule.FAVORITES, "password:1"))
        selection.toggle(VaultOverviewModule.FAVORITES, "password:1")
        assertEquals(listOf("password:1"), selection.keys)
        assertEquals(VaultOverviewModule.FAVORITES, selection.module)
        assertFalse(selection.isSelected(VaultOverviewModule.ITEMS, "password:1"))
    }

    @Test fun theSharedBackActionExitsOverviewSelectionAndRightSwipeCanDeselect() {
        val sharedKeys = mutableListOf<String>()
        val selection = VaultOverviewSelectionState(sharedKeys)
        selection.toggle(VaultOverviewModule.ITEMS, "password:1")
        selection.toggle(VaultOverviewModule.ITEMS, "password:1")
        assertNull(selection.module)
        selection.toggle(VaultOverviewModule.FAVORITES, "password:1")
        sharedKeys.clear()
        assertNull(selection.module)
        selection.toggle(VaultOverviewModule.ITEMS, "password:2")
        assertEquals(listOf("password:2"), sharedKeys)
        assertEquals(VaultOverviewModule.ITEMS, selection.module)
    }

    @Test fun collapsingHidingOrLosingAccessToASectionDropsItsSelection() {
        val selection = VaultOverviewSelectionState()
        val rows = List(12) { row(it) }
        val snapshot = VaultOverviewSnapshot("all", frequentItems = rows, favorites = rows)
        val config = VaultOverviewConfig()
        val preview = snapshot.selectablePreview(VaultOverviewModule.FAVORITES, config)
        selection.selectAll(VaultOverviewModule.FAVORITES, preview.map { it.key })
        assertEquals(rows.take(3).map { it.key }, selection.keys)
        selection.retainVisible(setOf(rows[1].key))
        assertEquals(listOf(rows[1].key), selection.keys)
        val collapsed = config.copy(collapsed = config.collapsed + VaultOverviewModule.FAVORITES.name)
        selection.retainVisible(snapshot.selectablePreview(selection.module, collapsed).mapTo(hashSetOf()) { it.key })
        assertNull(selection.module)
        assertTrue(snapshot.selectablePreview(VaultOverviewModule.ITEMS,
            config.copy(hidden = setOf(VaultOverviewModule.ITEMS.name))).isEmpty())
        selection.toggle(VaultOverviewModule.ITEMS, rows.first().key)
        selection.retainVisible(emptySet())
        assertNull(selection.module)
    }

    @Test fun selectAllCannotIncludeUnshownOrJustRemovedFrequentItems() {
        val rows = List(20) { row(it) }
        val snapshot = VaultOverviewSnapshot("local", frequentItems = rows.take(8), favorites = rows)
        val config = VaultOverviewConfig().removeFrequentItems(listOf(rows[0].overviewIdentity()))
        val selection = VaultOverviewSelectionState()
        selection.selectAll(VaultOverviewModule.ITEMS,
            snapshot.selectablePreview(VaultOverviewModule.ITEMS, config).map { it.key })
        assertEquals(rows.subList(1, 8).map { it.key }, selection.keys)
        assertEquals(rows.take(3), snapshot.selectablePreview(VaultOverviewModule.FAVORITES, config))
    }

    private fun row(index: Int) = VaultV2Item("password:$index", VaultV2ItemType.PASSWORD,
        "Entry $index", "account", true, "$index", emptyList())

    @Test fun searchActionsOnlyUseCurrentResultsAndNeverInheritFrequentRemoval() {
        val selection = VaultOverviewSelectionState()
        selection.toggle(VaultOverviewModule.ITEMS, "password:1")
        selection.updateSearchResults(listOf(row(2), row(3)))
        assertNull(selection.module)
        assertTrue(selection.keys.isEmpty())
        selection.toggleSearch("password:2")
        selection.selectSearch(listOf("password:2", "password:3", "password:4"))
        assertEquals(listOf("password:2", "password:3"), selection.keys)
        assertNull(selection.module)
        selection.updateSearchResults(listOf(row(3)))
        assertEquals(listOf("password:3"), selection.keys)
        selection.clear()
        assertEquals(listOf(row(3)), selection.searchResults)
        selection.toggleSearch("password:3")
        selection.exitSearch()
        assertTrue(selection.keys.isEmpty())
        assertNull(selection.searchResults)
    }
}
