package takagi.ru.monica.ui.cardwallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack

@RunWith(AndroidJUnit4::class)
class WalletStackNativeProjectionTest {
    private fun card(id: Long) = WalletListItem(id, WalletListItemType.BANK_CARD,
        SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Card $id", itemData = "must-not-cross-jni"))

    @Test fun nativeMatchesKotlinAcrossSavedOrderFiltersDuplicatesCoversAndSelection() {
        val random = Random(311)
        repeat(60) { iteration ->
            val all = (1L..random.nextInt(1, 1_201).toLong()).map(::card)
            val stacks = all.shuffled(random).take(all.size * 4 / 5).chunked(11).mapIndexed { index, members ->
                val ids = members.map { it.id }
                WalletStack("stack-$index", ids + ids.first() + (10_000L + index),
                    coverId = if (index % 2 == 0) ids.last() else 10_000L + index)
            }.shuffled(random)
            val visible = all.shuffled(random).filter { random.nextInt(4) != 0 }
            for (selection in listOf(false, true)) {
                val expected = projectWalletStacksKotlin(visible, stacks, selectionMode = selection)
                assertEquals("iteration=$iteration selection=$selection", expected,
                    requireNotNull(projectWalletStacksNative(visible, stacks, selection)))
                assertEquals(expected, projectWalletStacks(visible, stacks, selectionMode = selection))
                assertEquals(projectWalletStacksKotlin(visible, stacks, true, selection),
                    projectWalletStacks(visible, stacks, true, selection))
            }
        }
    }

    @Test fun invalidLegacySnapshotsFallBackWithoutChangingTheirExistingBehavior() {
        val rows = (1L..300L).map(::card)
        val cases = listOf(
            rows to listOf(WalletStack("a", listOf(1, 2)), WalletStack("b", listOf(2, 3))),
            rows to listOf(WalletStack("a", listOf(1, 2)), WalletStack("a", listOf(3, 4))),
            (rows + rows.first()) to listOf(WalletStack("a", listOf(1, 2)))
        )
        for ((cards, stacks) in cases) {
            for (selection in listOf(false, true)) {
                assertNull(projectWalletStacksNative(cards, stacks, selection))
                assertEquals(projectWalletStacksKotlin(cards, stacks, selectionMode = selection),
                    projectWalletStacks(cards, stacks, selectionMode = selection))
            }
        }
    }

    @Test fun nativeHandlesEmptySnapshotsAndFullWidthIds() {
        val cards = listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE).map(::card)
        val stacks = listOf(WalletStack("extremes", listOf(Long.MAX_VALUE, Long.MIN_VALUE)))
        for (selection in listOf(false, true)) {
            assertEquals(projectWalletStacksKotlin(cards, stacks, selectionMode = selection),
                requireNotNull(projectWalletStacksNative(cards, stacks, selection)))
            assertEquals(emptyList<WalletStackListEntry>(), projectWalletStacksNative(emptyList(), stacks, selection))
            assertEquals(projectWalletStacksKotlin(cards, emptyList()),
                requireNotNull(projectWalletStacksNative(cards, emptyList(), selection)))
        }
    }
}
