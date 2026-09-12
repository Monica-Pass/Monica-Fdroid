package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.ui.LocalAnimatedVisibilityScope
import takagi.ru.monica.ui.navigation.easyNotesScreenEnter
import takagi.ru.monica.ui.navigation.easyNotesScreenExit

@RunWith(AndroidJUnit4::class)
class WalletStackNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val cards = (1L..8L).map { id ->
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
    private val stack = WalletStackListEntry.Stack(WalletStack("navigation", cards.map { it.id }), cards)
    private var sourceReady by mutableStateOf(true)
    private var sourceEntry by mutableStateOf<WalletStackListEntry.Stack?>(stack)
    private val collapses = AtomicInteger()
    private val mainBacks = AtomicInteger()
    private val mainClicks = AtomicInteger()
    private lateinit var dispatchBack: () -> Unit

    private fun showNavigation() {
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                SideEffect { dispatchBack = dispatcher::onBackPressed }
                NavHost(nav, startDestination = "wallet") {
                    composable(
                        "wallet",
                        exitTransition = { easyNotesScreenExit() },
                        popEnterTransition = { easyNotesScreenEnter() }
                    ) {
                        var focused by rememberSaveable { mutableStateOf(4L) }
                        var expanded by rememberSaveable { mutableStateOf(true) }
                        val origin = with(LocalDensity.current) {
                            Rect(24.dp.toPx(), 100.dp.toPx(), 384.dp.toPx(), 325.dp.toPx())
                        }
                        val preview = rememberWalletStackPreview(
                            stackId = stack.stack.id.takeIf { expanded },
                            entry = sourceEntry.takeIf { expanded },
                            originBounds = origin,
                            isReady = sourceReady
                        )
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                            WalletStackOverlayHost {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                                    Text("Wallet underneath", Modifier.testTag("wallet_underneath"))
                                    Button(
                                        onClick = { mainClicks.incrementAndGet() },
                                        modifier = Modifier.align(Alignment.BottomEnd).testTag("wallet_underneath_action")
                                    ) { Text("Underlying action") }
                                    BackHandler { mainBacks.incrementAndGet() }
                                }
                                if (preview != null) {
                                    WalletStackBrowser(
                                        entry = preview.entry,
                                        originBounds = preview.originBounds,
                                        initialCardId = focused,
                                        animateEntrance = false,
                                        onOpened = {},
                                        onFocusedCardChanged = { focused = it },
                                        onCollapseStart = {},
                                        onRevealCover = {},
                                        onDismiss = { expanded = false; collapses.incrementAndGet() },
                                        onOpenCard = { nav.navigate("detail") },
                                        onManage = {}
                                    )
                                }
                            }
                        }
                    }
                    composable(
                        "detail",
                        enterTransition = { easyNotesScreenEnter() },
                        popExitTransition = { easyNotesScreenExit() }
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(onClick = { nav.popBackStack() }, modifier = Modifier.testTag("detail_back")) {
                                Text("Return to stack")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("wallet-return-tests"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun returningToTheStackAnimatesTheFocusedCardWithThePage() {
        showNavigation()
        val card = compose.onNodeWithTag("wallet_stack_card_4")
        val resting = card.fetchSemanticsNode().boundsInWindow
        capture("stack-resting")
        card.performClick()
        compose.onNodeWithTag("detail_back").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("detail_back").performClick()
            compose.mainClock.advanceTimeBy(80)
            val first = card.fetchSemanticsNode().boundsInWindow
            capture("return-080")
            assertTrue(
                "Returning stack appeared at full size before the detail finished leaving: ${first.width} / ${resting.width}",
                first.width < resting.width * 0.99f
            )
            var previousWidth = first.width
            repeat(5) {
                compose.mainClock.advanceTimeBy(64)
                val frame = card.fetchSemanticsNode().boundsInWindow
                if (it == 0 || it == 2 || it == 4) capture("return-${144 + it * 64}")
                assertTrue("Return motion jumped backwards", frame.width >= previousWidth - 0.5f)
                assertTrue("Return motion overshot the card", frame.width <= resting.width + 0.5f)
                previousWidth = frame.width
            }
            compose.mainClock.advanceTimeBy(160)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        card.assertIsDisplayed()
        val restored = card.fetchSemanticsNode().boundsInWindow
        assertEquals(resting.left, restored.left, 2f)
        assertEquals(resting.top, restored.top, 2f)
        assertEquals(resting.width, restored.width, 2f)
    }

    @Test fun returningKeepsTheFocusedPreviewUntilTheWholeSourceIsReady() {
        showNavigation()
        val card = compose.onNodeWithTag("wallet_stack_card_4")
        val resting = card.fetchSemanticsNode().boundsInWindow
        card.performClick()
        compose.onNodeWithTag("detail_back").assertIsDisplayed()
        compose.runOnIdle {
            sourceReady = false
            sourceEntry = stack.copy(cards = cards.take(2))
        }
        compose.onNodeWithTag("detail_back").performClick()
        card.assertIsDisplayed()
        assertEquals(resting.top, card.fetchSemanticsNode().boundsInWindow.top, 2f)
        compose.runOnIdle {
            sourceEntry = stack.copy(cards = cards.filterNot { it.id == 4L })
            sourceReady = true
        }
        compose.onNodeWithTag("wallet_stack_card_4").assertDoesNotExist()
        compose.onNodeWithTag("wallet_stack_card_5").assertIsDisplayed()
    }

    @Test fun aRemovedStackDoesNotKeepShowingTheRetainedPreview() {
        showNavigation()
        compose.onNodeWithTag("wallet_stack_card_4").performClick()
        compose.onNodeWithTag("detail_back").assertIsDisplayed()
        compose.runOnIdle {
            sourceReady = false
            sourceEntry = null
        }
        compose.onNodeWithTag("detail_back").performClick()
        compose.onNodeWithTag("wallet_stack_card_4").assertIsDisplayed()
        compose.runOnIdle { sourceReady = true }
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
        compose.onNodeWithTag("wallet_underneath").assertIsDisplayed()
    }

    @Test fun systemBackReturnsToTheStackBeforeCollapsingIt() {
        showNavigation()
        compose.onNodeWithTag("wallet_stack_card_4").performClick()
        compose.onNodeWithTag("detail_back").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("wallet_stack_card_4").assertIsDisplayed()
        assertEquals(0, collapses.get())
        Espresso.pressBack()
        compose.waitUntil(10_000) { collapses.get() == 1 }
        compose.onNodeWithTag("wallet_underneath").assertIsDisplayed()
        assertEquals(0, mainBacks.get())
    }

    @Test fun returnAnimationKeepsBackAndTouchesAwayFromTheMainPage() {
        showNavigation()
        compose.onNodeWithTag("wallet_stack_card_4").performClick()
        compose.onNodeWithTag("detail_back").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("detail_back").performClick()
            compose.mainClock.advanceTimeBy(80)
            val underlying = compose.onNodeWithTag("wallet_underneath_action", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInWindow
            val root = compose.onRoot().fetchSemanticsNode().boundsInWindow
            compose.onRoot().performTouchInput { click(underlying.center - root.topLeft) }
            compose.runOnIdle { dispatchBack() }
            assertEquals(0, mainClicks.get())
            assertEquals(0, mainBacks.get())
            assertEquals(0, collapses.get())
            compose.mainClock.advanceTimeBy(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("wallet_stack_card_4").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(10_000) { collapses.get() == 1 }
        compose.onNodeWithTag("wallet_underneath_action").performClick()
        assertEquals(1, mainClicks.get())
    }
}
