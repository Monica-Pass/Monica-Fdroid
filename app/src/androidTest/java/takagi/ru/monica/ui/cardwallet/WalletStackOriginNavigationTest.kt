package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.ui.LocalAnimatedVisibilityScope
import takagi.ru.monica.ui.navigation.*

/** Exercise the real measured cover, preview retention, navigation and collapse overlay together. */
@RunWith(AndroidJUnit4::class)
class WalletStackOriginNavigationTest {
    @get:Rule val compose = createComposeRule()
    private var remeasure by mutableIntStateOf(0)
    private var recorded: Rect? = null
    private var rawPosition = Offset.Zero
    private var revealed = false
    private var dismissed = false
    private val cards = (1L..3L).map { id -> WalletListItem(id, WalletListItemType.BANK_CARD,
        SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Origin fixture $id", itemData = "{}"),
        bankCardData = BankCardData(cardNumber = "4111111111111111", cardholderName = "TEST",
            expiryMonth = "09", expiryYear = "2030", bankName = "Test bank")) }
    private val entry = WalletStackListEntry.Stack(WalletStack("origin", cards.map { it.id }), cards)

    private fun show() {
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "wallet") {
                    composable("wallet", exitTransition = { easyNotesScreenExit() },
                        popEnterTransition = { easyNotesScreenEnter() }) {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                            var expanded by rememberSaveable { mutableStateOf(false) }
                            var origin by remember { mutableStateOf<Rect?>(null) }
                            var reveal by remember { mutableStateOf(false) }
                            val preview = rememberWalletStackPreview("origin".takeIf { expanded },
                                entry.takeIf { expanded }, origin, isReady = true)
                            WalletStackOverlayHost {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                                    WalletStackCard(entry, onClick = { expanded = true; reveal = false },
                                        onLongClick = {}, onManage = {},
                                        onCoverBounds = { origin = it; recorded = it },
                                        coverVisible = !expanded || reveal, controlsVisible = !expanded,
                                        // Force a real layout during navigation, as async wallet data can do.
                                        // Bottom padding changes; the resting cover's position and size do not.
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 96.dp,
                                            bottom = (remeasure % 2).dp)
                                            .onGloballyPositioned { rawPosition = it.positionInWindow() })
                                }
                                if (preview != null) WalletStackBrowser(preview.entry, preview.originBounds,
                                    initialCardId = 1, animateEntrance = false, onOpened = {},
                                    onFocusedCardChanged = {}, onCollapseStart = {},
                                    onRevealCover = { reveal = true; revealed = true },
                                    onDismiss = { expanded = false; dismissed = true },
                                    onOpenCard = { nav.navigate("detail") }, onManage = {})
                            }
                        }
                    }
                    composable("detail", enterTransition = { easyNotesScreenEnter() },
                        popExitTransition = { easyNotesScreenExit() }) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            Button(onClick = { nav.popBackStack() }, modifier = Modifier.testTag("origin_detail_back")) {
                                Text("Back")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir("wallet-origin-tests"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun assertSame(expected: Rect, actual: Rect) {
        assertEquals("left", expected.left, actual.left, 1f)
        assertEquals("top", expected.top, actual.top, 1f)
        assertEquals("width", expected.width, actual.width, 1f)
        assertEquals("height", expected.height, actual.height, 1f)
    }

    @Test fun movingNavigationFramesCannotReplaceTheRestingOrigin() {
        show()
        val original = checkNotNull(recorded)
        compose.onNodeWithTag("wallet_stack_origin").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("wallet_stack_card_1").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wallet_stack_card_1").performClick()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { remeasure++ }
            compose.waitForIdle()
            assertTrue("Probe must run while the page position is transformed", (rawPosition - original.topLeft).getDistance() > 0.25f)
            assertSame(original, checkNotNull(recorded))
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("origin_detail_back").performClick()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { remeasure++ }
            compose.waitForIdle()
            assertSame(original, checkNotNull(recorded))
            compose.mainClock.advanceTimeBy(600)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        assertSame(original, checkNotNull(recorded))
    }

    @Test fun repeatedDetailReturnsCollapseOntoTheUnmovedListCover() {
        show()
        val cover = compose.onNodeWithTag("wallet_stack_cover", useUnmergedTree = true)
        val original = cover.fetchSemanticsNode().boundsInWindow
        capture("before")
        repeat(3) {
            compose.onNodeWithTag("wallet_stack_origin").performClick()
            compose.onNodeWithTag("wallet_stack_card_1").performClick()
            compose.onNodeWithTag("origin_detail_back").performClick()
            compose.onNodeWithTag("wallet_stack_card_1").assertIsDisplayed()
            compose.runOnIdle { revealed = false; dismissed = false }
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("wallet_stack_collapse").performClick()
                compose.mainClock.advanceTimeUntil(2_000) { revealed }
                assertFalse(dismissed)
                assertSame(original, compose.onNodeWithTag("wallet_stack_card_1").fetchSemanticsNode().boundsInWindow)
                compose.mainClock.advanceTimeBy(100)
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
            assertTrue(dismissed)
            assertSame(original, cover.fetchSemanticsNode().boundsInWindow)
        }
        capture("after-three-returns")
    }
}
