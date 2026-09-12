package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.BankCardData

@RunWith(AndroidJUnit4::class)
class WalletStackBrowserTest {
    @get:Rule val compose = createComposeRule()

    private val focused = AtomicLong(1)
    private val collapsedCover = AtomicLong(0)
    private val dismissals = AtomicInteger(0)
    private val reveals = AtomicInteger(0)
    private val managementRequests = AtomicInteger(0)

    private fun showBrowser(count: Int = 8, enterAnimated: Boolean = false) {
        val cards = (1L..count.toLong()).map { id ->
            WalletListItem(
                id, WalletListItemType.BANK_CARD,
                SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Card $id", itemData = "{}"),
                bankCardData = BankCardData(
                    cardNumber = "411111111111${id.toString().padStart(4, '0')}",
                    cardholderName = "MONICA DEMO", expiryMonth = "09", expiryYear = "2030",
                    bankName = "Demo bank $id"
                )
            )
        }
        val entry = WalletStackListEntry.Stack(WalletStack("test", cards.map { it.id }), cards)
        var visible by mutableStateOf(true)
        var detailId by mutableStateOf<Long?>(null)
        var animateEntrance by mutableStateOf(enterAnimated)
        var coverId by mutableStateOf(1L)
        var coverRevealed by mutableStateOf(false)
        var origin by mutableStateOf<Rect?>(null)
        compose.setContent {
            MaterialTheme {
                WalletStackOverlayHost {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                        Text("Wallet underneath", Modifier.padding(bottom = 32.dp), style = MaterialTheme.typography.headlineMedium)
                        WalletStackCard(
                            entry = entry.copy(stack = entry.stack.copy(coverId = coverId)),
                            onClick = { visible = true; animateEntrance = true; coverRevealed = false },
                            onLongClick = {}, onManage = { managementRequests.incrementAndGet() },
                            onCoverBounds = { origin = it },
                            coverVisible = !visible || coverRevealed || detailId != null,
                            controlsVisible = !visible || detailId != null
                        )
                    }
                    if (detailId != null) {
                        Button(onClick = { detailId = null }) { Text("Return from card $detailId") }
                    } else if (visible) {
                        WalletStackBrowser(
                            entry = entry,
                            originBounds = origin,
                            initialCardId = focused.get(),
                            animateEntrance = animateEntrance,
                            onOpened = { animateEntrance = false },
                            onFocusedCardChanged = { focused.set(it) },
                            onCollapseStart = { collapsedCover.set(it); coverId = it },
                            onRevealCover = { coverRevealed = true; reveals.incrementAndGet() },
                            onDismiss = { dismissals.incrementAndGet(); visible = false },
                            onOpenCard = { detailId = it.id },
                            onManage = {}
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
    }

    private fun capture(name: String, browser: Boolean = true) {
        val node = if (browser) compose.onNodeWithTag("wallet_stack_browser") else compose.onRoot()
        val image = node.captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("wallet-stack-tests"), "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun browsingKeepsCollapseButtonFixedAndCollapsesToTheBrowsedCard() {
        showBrowser()
        capture("expanded")
        val before = compose.onNodeWithTag("wallet_stack_collapse").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp() }
        compose.waitUntil(10_000) { focused.get() > 1 }
        compose.waitForIdle()
        val browsedId = focused.get()
        capture("browsed")
        val after = compose.onNodeWithTag("wallet_stack_collapse").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wallet_stack_collapse").performClick()
            compose.mainClock.advanceTimeBy(160)
            capture("gathering")
            compose.mainClock.advanceTimeBy(128)
            capture("returning")
            compose.mainClock.advanceTimeBy(160)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { dismissals.get() == 1 }
        assertEquals(browsedId, collapsedCover.get())
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
        compose.onNodeWithText("Wallet underneath").assertIsDisplayed()
        capture("collapsed", browser = false)
    }

    @Test fun systemBackUsesTheSameCollapsePath() {
        showBrowser()
        Espresso.pressBack()
        compose.waitUntil(10_000) { dismissals.get() == 1 }
        assertEquals(1L, collapsedCover.get())
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
    }

    @Test fun collapseInterruptsAFlingAndRepeatedTapsDismissOnlyOnce() {
        showBrowser()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp(durationMillis = 120) }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("wallet_stack_collapse").performTouchInput { doubleClick() }
            compose.mainClock.advanceTimeBy(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { dismissals.get() == 1 }
        assertTrue(collapsedCover.get() in 2L..8L)
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
    }

    @Test fun reachingTheFirstCardDoesNotDismissTheStack() {
        showBrowser()
        compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, dismissals.get())
        assertEquals(1L, focused.get())
        compose.onNodeWithTag("wallet_stack_collapse").assertIsDisplayed()
    }

    @Test fun returningFromDetailsRestoresTheSameFocusedCard() {
        showBrowser()
        compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp() }
        compose.waitUntil(10_000) { focused.get() > 1 }
        compose.waitForIdle()
        val id = focused.get()
        compose.onNodeWithTag("wallet_stack_card_$id").performClick()
        compose.onNodeWithText("Return from card $id").performClick()
        compose.waitForIdle()
        assertEquals(id, focused.get())
        compose.onNodeWithTag("wallet_stack_card_$id").assertIsDisplayed()
        compose.onNodeWithTag("wallet_stack_collapse").performClick()
        compose.waitUntil(10_000) { dismissals.get() == 1 }
        assertEquals(id, collapsedCover.get())
    }

    @Test fun aLargeStackOnlyComposesNearbyCardFaces() {
        showBrowser(count = 1_000)
        compose.onNodeWithTag("wallet_stack_card_1").assertIsDisplayed()
        compose.onNodeWithTag("wallet_stack_card_500").assertDoesNotExist()
        compose.onNodeWithTag("wallet_stack_card_1000").assertDoesNotExist()
        assertEquals(0, dismissals.get())
    }

    @Test fun collapseHandsOffTheSameCardBeforeRemovingTheOverlay() {
        showBrowser(enterAnimated = true)
        compose.waitForIdle()
        compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp(durationMillis = 600) }
        compose.waitForIdle()
        val id = focused.get()
        var finalFrame: Bitmap? = null
        var finalBounds: Rect? = null
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wallet_stack_collapse").performClick()
            compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { reveals.get() == 1 }
            assertEquals(0, dismissals.get())
            assertEquals(id, collapsedCover.get())
            val card = compose.onNodeWithTag("wallet_stack_card_$id")
            finalBounds = card.fetchSemanticsNode().boundsInWindow
            finalFrame = card.captureToImage().asAndroidBitmap()
            capture("handoff-before")
            compose.mainClock.advanceTimeBy(80)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { dismissals.get() == 1 }
        assertEquals(1, reveals.get())
        val cover = compose.onNodeWithTag("wallet_stack_cover", useUnmergedTree = true)
        val expectedBounds = requireNotNull(finalBounds)
        val coverBounds = cover.fetchSemanticsNode().boundsInWindow
        assertEquals(expectedBounds.left, coverBounds.left, 0.01f)
        assertEquals(expectedBounds.top, coverBounds.top, 0.01f)
        assertEquals(expectedBounds.right, coverBounds.right, 0.01f)
        assertEquals(expectedBounds.bottom, coverBounds.bottom, 0.01f)
        val coverFrame = cover.captureToImage().asAndroidBitmap()
        assertEquals(finalFrame!!.width, coverFrame.width)
        assertEquals(finalFrame!!.height, coverFrame.height)
        val controlsBounds = compose.onNodeWithTag("wallet_stack_controls", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow.translate(-coverBounds.topLeft).inflate(1f)
        // Compare the actual card interior across the overlay handoff, excluding the outer
        // shadow/rounded corners and controls, which fade in separately at their list size.
        var difference = 0L
        var channels = 0L
        for (y in coverFrame.height / 10 until coverFrame.height * 9 / 10) {
            for (x in coverFrame.width / 10 until coverFrame.width * 9 / 10) {
                if (x >= controlsBounds.left && x < controlsBounds.right &&
                    y >= controlsBounds.top && y < controlsBounds.bottom) continue
                val before = finalFrame!!.getPixel(x, y)
                val after = coverFrame.getPixel(x, y)
                for (shift in 0..16 step 8) {
                    difference += abs((before shr shift and 255) - (after shr shift and 255))
                    channels++
                }
            }
        }
        assertTrue("Card changed across handoff: ${difference.toDouble() / channels}",
            difference.toDouble() / channels < 1.0)
        capture("handoff-after", browser = false)
        compose.onNodeWithTag("wallet_stack_manage").assertIsDisplayed()
    }

    @Test fun countCapsuleKeepsItsRestingSizeThroughoutCollapseAndReveal() {
        showBrowser(count = 12)
        val restingBounds = compose.onNodeWithTag("wallet_stack_controls", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wallet_stack_collapse").performClick()
            for (elapsed in listOf(160L, 128L, 112L, 32L, 48L, 96L)) {
                compose.mainClock.advanceTimeBy(elapsed)
                val capsules = compose.onAllNodesWithTag("wallet_stack_controls", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                assertTrue(capsules.isNotEmpty())
                capsules.forEach { capsule ->
                    val bounds = capsule.boundsInWindow
                    assertEquals(restingBounds.width, bounds.width, 0.01f)
                    assertEquals(restingBounds.height, bounds.height, 0.01f)
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { dismissals.get() == 1 }
        compose.onNodeWithTag("wallet_stack_manage").performClick()
        assertEquals(1, managementRequests.get())
        capture("capsule-settled", browser = false)
    }

    @Test fun draggingLiftsTheOutgoingCardBeforeItSlidesIntoTheBackSlot() {
        showBrowser()
        val scroll = compose.onNodeWithTag("wallet_stack_scroll")
        var liftedTop = 0f
        compose.mainClock.autoAdvance = false
        try {
            scroll.performTouchInput { down(Offset(centerX, height * 0.82f)) }
            for (step in 1..6) {
                scroll.performTouchInput { moveBy(Offset(0f, -height * 0.06f), delayMillis = 64) }
                compose.mainClock.advanceTimeByFrame()
                capture("drag-$step")
                if (step == 3) {
                    val outgoing = compose.onNodeWithTag("wallet_stack_card_1").fetchSemanticsNode().boundsInRoot
                    val incoming = compose.onNodeWithTag("wallet_stack_card_2").fetchSemanticsNode().boundsInRoot
                    assertTrue("Cards should clear each other before changing layers", outgoing.bottom < incoming.top)
                    liftedTop = outgoing.top
                }
            }
            scroll.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1_000)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertEquals(2L, focused.get())
        val tucked = compose.onNodeWithTag("wallet_stack_card_1").fetchSemanticsNode().boundsInRoot
        assertTrue("The outgoing card should settle back below its lifted position", tucked.top > liftedTop)
        capture("drag-settled")
    }
}
