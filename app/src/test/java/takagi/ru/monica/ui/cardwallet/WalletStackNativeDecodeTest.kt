package takagi.ru.monica.ui.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack

class WalletStackNativeDecodeTest {
    private fun cards(vararg ids: Long) = ids.map { id ->
        WalletListItem(id, WalletListItemType.BANK_CARD,
            SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Card $id", itemData = "{}"))
    }

    @Test fun `native mapping preserves group order member order identity and selection boundaries`() {
        val cards = cards(8, 5, 3, 7, 2, 1, 4, 6)
        val stacks = listOf(WalletStack("first", listOf(2, 1)), WalletStack("second", listOf(4, 3), coverId = 3))
        val indices = intArrayOf(1, 2, 1, 2, 6, 2, 0, 2, 4, 5, 4, 0, 1, 3, 7)
        for (selection in listOf(false, true)) {
            assertEquals(projectWalletStacksKotlin(cards, stacks, selectionMode = selection),
                decodeWalletStackProjection(cards, stacks, selection, indices))
        }
        val group = decodeWalletStackProjection(cards, stacks, false, indices)!!.first() as WalletStackListEntry.Stack
        assertSame(stacks[1], group.stack)
        assertSame(cards[2], group.cover)
    }

    @Test fun `filtered cover and single member selection use original metadata`() {
        val cards = cards(9, 2)
        val stack = WalletStack("trip", listOf(1, 2, 3), coverId = 3)
        val selection = decodeWalletStackProjection(cards, listOf(stack), true, intArrayOf(1, 1, 0, 1, 1, 1, 0))!!
        assertEquals(projectWalletStacksKotlin(cards, listOf(stack), selectionMode = true), selection)
        val header = selection.first() as WalletStackListEntry.SelectionHeader
        assertSame(cards[1], header.cover)
        assertSame(stack, header.stack)
        assertEquals(listOf(1L, 2L, 3L), stack.memberIds)
        assertNull(decodeWalletStackProjection(cards, listOf(stack), false, intArrayOf(1, 1, 0, 1, 1, 1, 0)))
    }

    @Test fun `malformed native output cannot omit duplicate or invent a card or group`() {
        val cards = cards(1, 2, 3, 4)
        val stacks = listOf(WalletStack("first", listOf(1, 2)), WalletStack("second", listOf(3, 4)))
        val invalid = listOf(
            intArrayOf(), intArrayOf(2, 0, 4, 0, 1, 2, 3), intArrayOf(1, -1, 0),
            intArrayOf(1, 3, 0), intArrayOf(1, 1), intArrayOf(1, 1, -1, 2, 0, 1, 2, 2, 3),
            intArrayOf(1, 1, 2, 2, 0, 1, 2, 2, 3), intArrayOf(1, 1, 0, -1, 0),
            intArrayOf(1, 1, 0, Int.MAX_VALUE, 0), intArrayOf(1, 1, 0, 2, 0, 0, 2, 2, 3),
            intArrayOf(1, 1, 0, 2, -1, 1, 2, 2, 3), intArrayOf(1, 1, 0, 2, 4, 1, 2, 2, 3),
            intArrayOf(1, 2, 0, 2, 0, 1, 0, 2, 2, 3, 0), // repeated group
            intArrayOf(1, 1, 0, 2, 0, 1, 2, 1, 2), // member repeated as single
            intArrayOf(1, 1, 0, 2, 0, 1, 1, 2), // omitted card
            intArrayOf(1, 0, 4, 0, 1, 2, 3, 7), // trailing payload
            intArrayOf(1, 0, 3, 0, 1, 2), intArrayOf(1, 0, -1)
        )
        for (output in invalid) {
            for (selection in listOf(false, true)) {
                assertNull(output.contentToString(), decodeWalletStackProjection(cards, stacks, selection, output))
            }
        }
    }

    @Test fun `empty or entirely independent native output does not add empty sections`() {
        assertEquals(emptyList<WalletStackListEntry>(),
            decodeWalletStackProjection(emptyList(), emptyList(), true, intArrayOf(1, 0, 0)))
        val cards = cards(8, 9)
        val stacks = listOf(WalletStack("hidden", listOf(1, 2)))
        assertEquals(projectWalletStacksKotlin(cards, stacks, selectionMode = true),
            decodeWalletStackProjection(cards, stacks, true, intArrayOf(1, 0, 2, 0, 1)))
    }

    @Test fun `wallets above the native threshold still work without an Android library`() {
        val cards = cards(*(1L..300L).toList().toLongArray())
        val stacks = listOf(WalletStack("trip", (1L..250L).toList().reversed()))
        for (selection in listOf(false, true)) {
            assertEquals(projectWalletStacksKotlin(cards, stacks, selectionMode = selection),
                projectWalletStacks(cards, stacks, selectionMode = selection))
        }
    }
}
