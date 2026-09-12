package takagi.ru.monica.ui.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack

class WalletStackProjectionTest {
    private fun card(id: Long) = WalletListItem(
        id, WalletListItemType.BANK_CARD,
        SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Card $id", itemData = "{}")
    )

    @Test fun `stacks precede manually sorted singles and keep saved internal order`() {
        val stack = WalletStack("travel", listOf(1, 2, 3), coverId = 2)
        val result = projectWalletStacks(listOf(9L, 3L, 1L, 2L, 8L).map(::card), listOf(stack))
        assertEquals(listOf("stack:travel", "card:9", "card:8"), result.map { it.key })
        val group = result[0] as WalletStackListEntry.Stack
        assertEquals(listOf(1L, 2L, 3L), group.cards.map { it.id })
        assertEquals(2L, group.cover.id)
    }

    @Test fun `category filters include only matching members without changing persisted membership`() {
        val stack = WalletStack("travel", listOf(1, 2, 3, 4), coverId = 4)
        val result = projectWalletStacks(listOf(card(3), card(1)), listOf(stack)).single() as WalletStackListEntry.Stack
        assertEquals(listOf(1L, 3L), result.cards.map { it.id })
        assertEquals(1L, result.cover.id)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.stack.memberIds)
        assertEquals(4L, result.stack.coverId)
    }

    @Test fun `a single remaining visible member is shown as an individual card`() {
        val stack = WalletStack("travel", listOf(1, 2))
        val result = projectWalletStacks(listOf(card(2), card(7)), listOf(stack))
        assertTrue(result.all { it is WalletStackListEntry.Single })
        assertEquals(listOf("card:2", "card:7"), result.map { it.key })
        assertEquals(listOf(1L, 2L), stack.memberIds)
    }

    @Test fun `search outside selection exposes every matching card in current list order`() {
        val stack = WalletStack("travel", listOf(1, 2, 3))
        val cards = listOf(card(3), card(2), card(1))
        val result = projectWalletStacks(cards, listOf(stack), showIndividualCards = true)
        assertEquals(listOf("card:3", "card:2", "card:1"), result.map { it.key })
        assertTrue(result.all { it is WalletStackListEntry.Single })
    }

    @Test fun `promoting the last browsed cover keeps the stack at the same list position`() {
        val cards = (1L..5).map(::card)
        val stack = WalletStack("travel", listOf(2, 4, 5))
        val before = projectWalletStacks(cards, listOf(stack))
        val after = projectWalletStacks(cards, listOf(stack.copy(coverId = 5)))
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals(5L, (after[0] as WalletStackListEntry.Stack).cover.id)
    }

    @Test fun `all stacks stay above singles when manual order puts individual cards first`() {
        val cards = listOf(8L, 5L, 3L, 7L, 2L, 1L, 4L, 6L).map(::card)
        val result = projectWalletStacks(cards, listOf(
            WalletStack("first", listOf(1, 2)), WalletStack("second", listOf(3, 4))
        ))
        assertEquals(listOf("stack:second", "stack:first", "card:8", "card:5", "card:7", "card:6"),
            result.map { it.key })
    }

    @Test fun `dissolving a stack restores the original single card sort order`() {
        val cards = listOf(5L, 2L, 7L, 1L, 3L).map(::card)
        val groups = listOf(WalletStack("group", listOf(1, 2, 3)))
        assertEquals("stack:group", projectWalletStacks(cards, groups).first().key)
        assertEquals(cards.map { "card:${it.id}" }, projectWalletStacks(cards, emptyList()).map { it.key })
    }

    @Test fun `selection expands stacks in saved order before the original sorted singles`() {
        val cards = listOf(8L, 5L, 3L, 7L, 2L, 1L, 4L, 6L).map(::card)
        val stacks = listOf(
            WalletStack("first", listOf(2, 1)),
            WalletStack("second", listOf(4, 3))
        )
        val result = projectWalletStacks(cards, stacks, selectionMode = true)
        assertEquals(
            listOf("stack:second", "card:4", "card:3", "stack:first", "card:2", "card:1",
                "selection:unstacked", "card:8", "card:5", "card:7", "card:6"),
            result.map { it.key }
        )
        assertEquals(
            projectWalletStacks(cards, stacks).filterIsInstance<WalletStackListEntry.Stack>().map { it.key },
            result.filterIsInstance<WalletStackListEntry.SelectionHeader>().filter { it.stack != null }.map { it.key }
        )
        assertEquals(result.size, result.map { it.key }.toSet().size)
    }

    @Test fun `filtered selection retains stack context without selecting hidden members`() {
        val stack = WalletStack("travel", listOf(1, 2, 3), coverId = 1)
        val result = projectWalletStacks(
            listOf(card(9), card(2)), listOf(stack), showIndividualCards = true, selectionMode = true
        )
        val header = result.first() as WalletStackListEntry.SelectionHeader
        assertEquals(listOf(2L), header.cards.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), header.stack!!.memberIds)
        assertEquals(setOf(2L, 9L), toggleWalletSelectionGroup(setOf(9L), header))
        assertEquals(listOf(2L, 9L), result.filterIsInstance<WalletStackListEntry.Single>().map { it.card.id })
    }

    @Test fun `selection without matching stacks does not add empty sections`() {
        val cards = listOf(card(9), card(7))
        val result = projectWalletStacks(cards, listOf(WalletStack("hidden", listOf(1, 2))), selectionMode = true)
        assertEquals(listOf("card:9", "card:7"), result.map { it.key })
        assertTrue(result.all { it is WalletStackListEntry.Single })
    }

    @Test fun `group toggles complete a partial selection and leave other groups untouched`() {
        val header = WalletStackListEntry.SelectionHeader(
            WalletStack("travel", listOf(1, 2, 3)), (1L..3).map(::card)
        )
        val allSelected = toggleWalletSelectionGroup(setOf(2L, 8L), header)
        assertEquals(setOf(1L, 2L, 3L, 8L), allSelected)
        assertEquals(setOf(8L), toggleWalletSelectionGroup(allSelected, header))
        assertEquals(setOf(1L, 2L, 3L), toggleWalletSelectionGroup(emptySet(), header))
    }

    @Test fun `wallet with only stacked cards does not show an empty independent section`() {
        val result = projectWalletStacks(listOf(card(2), card(1)),
            listOf(WalletStack("travel", listOf(1, 2))), selectionMode = true)
        assertEquals(listOf("stack:travel", "card:1", "card:2"), result.map { it.key })
    }

    @Test fun `independent card dragging uses card identities despite section headers`() {
        val cards = listOf(1L, 8L, 2L, 9L, 3L, 7L).map(::card)
        val stack = WalletStack("travel", listOf(2, 1))
        val entries = projectWalletStacks(cards, listOf(stack), selectionMode = true)
        val reordered = reorderWalletSingleCards(cards,
            entries.first { it.key == "card:7" }, entries.first { it.key == "card:8" })
        assertEquals(listOf(1L, 7L, 8L, 2L, 9L, 3L), reordered.map { it.id })
        assertEquals(listOf(2L, 1L), stack.memberIds)
        assertEquals(cards.map { it.id }.toSet(), reordered.map { it.id }.toSet())
    }

    @Test fun `dragging across a stack boundary or onto a heading leaves ordering unchanged`() {
        val cards = listOf(1L, 8L, 2L, 9L).map(::card)
        val entries = projectWalletStacks(cards, listOf(WalletStack("travel", listOf(1, 2))), selectionMode = true)
        val single = entries.first { it.key == "card:8" }
        val member = entries.first { it.key == "card:1" }
        val heading = entries.first { it.key == "stack:travel" }
        assertSame(cards, reorderWalletSingleCards(cards, single, heading))
        assertSame(cards, reorderWalletSingleCards(cards, single, member))
        assertSame(cards, reorderWalletSingleCards(cards, member, single))
        assertSame(cards, reorderWalletSingleCards(cards, null, single))
    }
}
