package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.BankCardData

@RunWith(AndroidJUnit4::class)
class WalletStackLoopTest {
    @get:Rule val compose = createComposeRule()
    private val focused = AtomicLong(1)
    private val collapsed = AtomicLong(0)
    private var loopEnabled by mutableStateOf(true)
    private var cards by mutableStateOf<List<WalletListItem>>(emptyList())

    private fun showBrowser(count: Int, initialId: Long = 1): StateRestorationTester {
        focused.set(initialId)
        cards = (1L..count.toLong()).map { id ->
            WalletListItem(id, WalletListItemType.BANK_CARD,
                SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Loop card $id", itemData = "{}"),
                bankCardData = BankCardData(cardNumber = "411111111111${id.toString().padStart(4, '0')}",
                    cardholderName = "MONICA DEMO", expiryMonth = "09", expiryYear = "2030",
                    bankName = "Demo bank $id"))
        }
        var visible by mutableStateOf(true)
        var detailId by mutableStateOf<Long?>(null)
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                MaterialTheme {
                    WalletStackOverlayHost {
                        if (detailId != null) {
                            Button(onClick = { detailId = null }) { Text("Return from ${detailId}") }
                        } else if (visible) {
                            WalletStackBrowser(
                                entry = WalletStackListEntry.Stack(WalletStack("loop", cards.map { it.id }), cards),
                                originBounds = null,
                                initialCardId = focused.get(),
                                animateEntrance = false,
                                onOpened = {},
                                onFocusedCardChanged = { focused.set(it) },
                                onCollapseStart = { collapsed.set(it) },
                                onRevealCover = {},
                                onDismiss = { visible = false },
                                onOpenCard = { detailId = it.id },
                                onManage = {},
                                loopEnabled = loopEnabled,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
        }
    }

    private fun moveFocus(forward: Boolean): Boolean {
        val action = compose.onNodeWithTag("wallet_stack_scroll").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions][if (forward) 0 else 1]
        val accepted = compose.runOnIdle { action.action() }
        compose.waitForIdle()
        return accepted
    }

    private fun swipeOneCard(forward: Boolean) {
        val cardHeight = compose.onNodeWithTag("wallet_stack_card_${focused.get()}")
            .fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("wallet_stack_scroll").performTouchInput {
            val travel = cardHeight * 1.45f * if (forward) 1f else -1f
            swipe(Offset(centerX, centerY + travel / 2f), Offset(centerX, centerY - travel / 2f), 600)
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("wallet_stack_browser").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("wallet-stack-tests"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun twoCardsLoopInBothDirectionsWithoutDuplicateAccessibleCards() {
        showBrowser(2)
        repeat(6) { step ->
            assertTrue(moveFocus(forward = true))
            assertEquals(if (step % 2 == 0) 2L else 1L, focused.get())
            compose.onAllNodesWithTag("wallet_stack_card_1").assertCountEquals(1)
            compose.onAllNodesWithTag("wallet_stack_card_2").assertCountEquals(1)
            compose.onAllNodesWithContentDescription("Loop card 1").assertCountEquals(1)
            compose.onAllNodesWithContentDescription("Loop card 2").assertCountEquals(1)
        }
        repeat(6) { step ->
            assertTrue(moveFocus(forward = false))
            assertEquals(if (step % 2 == 0) 2L else 1L, focused.get())
        }
        capture("loop-two-cards")
    }

    @Test fun loopAndOrdinaryModesRenderMatchingCardsIdentically() {
        showBrowser(12, initialId = 6)
        val loopFrame = compose.onNodeWithTag("wallet_stack_browser").captureToImage().asAndroidBitmap()
        capture("loop-shared-style")
        compose.runOnIdle { loopEnabled = false }
        compose.waitForIdle()
        assertEquals(6L, focused.get())
        val ordinaryFrame = compose.onNodeWithTag("wallet_stack_browser").captureToImage().asAndroidBitmap()
        capture("ordinary-shared-style")
        try {
            assertTrue("Only end wrapping should change when toggling loop mode", loopFrame.sameAs(ordinaryFrame))
        } finally {
            loopFrame.recycle()
            ordinaryFrame.recycle()
        }
    }

    @Test fun swipesCrossBothEndsAndDetailsAndCollapseKeepTheWrappedCard() {
        showBrowser(3, initialId = 3)
        swipeOneCard(forward = true)
        assertEquals(1L, focused.get())
        swipeOneCard(forward = false)
        assertEquals(3L, focused.get())
        swipeOneCard(forward = true)
        assertEquals(1L, focused.get())
        capture("loop-wrapped-front")
        compose.onNodeWithTag("wallet_stack_card_1").performClick()
        compose.onNodeWithText("Return from 1").performClick()
        assertEquals(1L, focused.get())
        compose.onNodeWithTag("wallet_stack_card_1").assertIsDisplayed()
        compose.onNodeWithTag("wallet_stack_collapse").performClick()
        compose.waitForIdle()
        assertEquals(1L, collapsed.get())
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
    }

    @Test fun switchingTheOptionKeepsFocusAndRestoresTheOrdinaryEndStop() {
        showBrowser(3)
        moveFocus(forward = false)
        assertEquals(3L, focused.get())
        compose.runOnIdle { loopEnabled = false }
        compose.waitForIdle()
        assertEquals(3L, focused.get())
        assertFalse(moveFocus(forward = true))
        swipeOneCard(forward = true)
        assertEquals(3L, focused.get())
        compose.runOnIdle { loopEnabled = true }
        assertTrue(moveFocus(forward = true))
        assertEquals(1L, focused.get())
    }

    @Test fun membershipChangesKeepTheFocusedIdentityAndHandleOneOrNoCards() {
        showBrowser(4, initialId = 4)
        compose.runOnIdle { cards = cards.reversed() }
        compose.waitForIdle()
        assertEquals(4L, focused.get())
        moveFocus(forward = true)
        assertEquals(3L, focused.get())
        compose.runOnIdle { cards = cards.filterNot { it.id == 3L } }
        compose.waitForIdle()
        assertEquals(2L, focused.get())
        compose.onNodeWithTag("wallet_stack_card_3").assertDoesNotExist()
        compose.runOnIdle { cards = cards.filter { it.id == 2L } }
        compose.waitForIdle()
        assertEquals(2L, focused.get())
        assertFalse(moveFocus(forward = true))
        swipeOneCard(forward = true)
        assertEquals(2L, focused.get())
        compose.runOnIdle { cards = emptyList() }
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
    }

    @Test fun wrappedFocusSurvivesSavedStateRestoration() {
        val restoration = showBrowser(5)
        moveFocus(forward = false)
        moveFocus(forward = false)
        assertEquals(4L, focused.get())
        // A stale caller hint must not replace the browser's saved card and position.
        focused.set(1L)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(4L, focused.get())
        compose.onNodeWithTag("wallet_stack_card_4").assertIsDisplayed()
    }

    @Test fun reversingAnActiveDragThenCollapsingKeepsAValidCard() {
        showBrowser(8, initialId = 8)
        compose.mainClock.autoAdvance = false
        try {
            val scroll = compose.onNodeWithTag("wallet_stack_scroll")
            scroll.performTouchInput { down(Offset(centerX, height * 0.8f)) }
            repeat(4) { step ->
                scroll.performTouchInput { moveBy(Offset(0f, -height * 0.065f), 64) }
                compose.mainClock.advanceTimeByFrame()
                capture("loop-drag-$step")
            }
            scroll.performTouchInput { moveBy(Offset(0f, height * 0.1f), 48); up() }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("wallet_stack_collapse").performClick()
            compose.mainClock.advanceTimeBy(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue(collapsed.get() in 1L..8L)
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
    }
}
