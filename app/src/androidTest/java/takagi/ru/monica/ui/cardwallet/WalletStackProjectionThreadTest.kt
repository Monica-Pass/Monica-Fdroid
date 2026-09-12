package takagi.ru.monica.ui.cardwallet

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack

@RunWith(AndroidJUnit4::class)
class WalletStackProjectionThreadTest {
    @get:Rule val compose = createComposeRule()

    @Test fun batchPackingAndMappingStayOffMainAndDoNotRepeatDuringStackAnimations() {
        val backing = (1L..5_000L).map { id ->
            WalletListItem(id, WalletListItemType.BANK_CARD,
                SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Card $id", itemData = "{}"))
        }
        val reads = AtomicInteger()
        val mainReads = AtomicInteger()
        val cards = object : AbstractList<WalletListItem>() {
            override val size: Int get() = backing.size
            override fun get(index: Int): WalletListItem {
                reads.incrementAndGet()
                if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet()
                return backing[index]
            }
        }
        val stacks = listOf(WalletStack("large", backing.map { it.id }))
        var selection by mutableStateOf(false)
        var focused by mutableLongStateOf(1)
        val rendered = AtomicReference<WalletStackProjection>()
        compose.setContent {
            MaterialTheme {
                WalletStackOverlayHost {
                    val projection = rememberWalletStackEntries(cards, stacks, false, selection).value
                    SideEffect { rendered.set(projection) }
                    if (!selection) {
                        (projection.entries.firstOrNull() as? WalletStackListEntry.Stack)?.let { entry ->
                            WalletStackBrowser(entry, null, focused, true,
                                onOpened = {}, onFocusedCardChanged = { focused = it },
                                onCollapseStart = {}, onRevealCover = {}, onDismiss = {},
                                onOpenCard = {}, onManage = {})
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { rendered.get()?.entries?.firstOrNull() is WalletStackListEntry.Stack }
        compose.waitForIdle()
        val snapshotReads = reads.get()
        repeat(3) {
            compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        assertTrue(focused > 1)
        assertEquals("Animation and focus changes must reuse the projected snapshot", snapshotReads, reads.get())
        compose.runOnIdle { selection = true }
        compose.waitUntil(10_000) { rendered.get()?.selectionMode == true }
        assertTrue(rendered.get().entries.first() is WalletStackListEntry.SelectionHeader)
        assertTrue(reads.get() > snapshotReads)
        assertEquals("Neither JNI packing nor result mapping may read the wallet on the UI thread", 0, mainReads.get())
    }
}
