package takagi.ru.monica.ui.cardwallet

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.ui.components.BankCardCard

@RunWith(AndroidJUnit4::class)
class WalletSelectionScrollTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var listState: LazyListState
    private val rendered = AtomicReference<List<WalletStackListEntry>>(emptyList())
    private var selecting by mutableStateOf(false)
    private var selectedIds by mutableStateOf(emptySet<Long>())
    private val stacks = (0 until 10).map { index ->
        val members = (index * 60 + 1L..index * 60 + 60L).toList()
        WalletStack("group-$index", members, coverId = members.last())
    }
    private val cards = ((1L..600L) + (1000L..1019L)).map { id ->
        WalletListItem(
            id, WalletListItemType.BANK_CARD,
            SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Card $id", itemData = "{}"),
            bankCardData = BankCardData(
                cardNumber = "4111111111111111", cardholderName = "MONICA DEMO",
                expiryMonth = "09", expiryYear = "2030"
            )
        )
    }

    private fun showWallet(search: Boolean = false) {
        val visibleCards = if (search) cards.reversed() else cards
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    listState = rememberLazyListState()
                    val projection = rememberWalletStackEntries(
                        visibleCards, stacks, showIndividualCards = search, selectionMode = selecting
                    ).value
                    val entries = projection.entries
                    val captureSelectionScrollAnchor = rememberWalletSelectionScrollAnchor(
                        listState, projection, selecting
                    )
                    SideEffect { rendered.set(entries) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("wallet_scroll_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(entries, key = { it.key }) { entry ->
                            when (entry) {
                                is WalletStackListEntry.Stack -> WalletStackCard(
                                    entry = entry,
                                    onClick = {},
                                    onLongClick = {
                                        captureSelectionScrollAnchor(entry.key, entry.cover.id)
                                        selectedIds = entry.cards.map(WalletListItem::id).toSet()
                                        selecting = true
                                    },
                                    onManage = {},
                                    onCoverBounds = {},
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                is WalletStackListEntry.SelectionHeader -> WalletSelectionSectionHeader(
                                    entry, selectedIds,
                                    onToggleSelection = { selectedIds = toggleWalletSelectionGroup(selectedIds, entry) },
                                    onManageStack = {}
                                )
                                is WalletStackListEntry.Single -> WalletSelectionCardFrame(
                                    entry, Modifier.testTag("wallet_card_${entry.card.id}")
                                ) {
                                    val selectCard = {
                                        if (!selecting) captureSelectionScrollAnchor(entry.key, entry.card.id)
                                        selectedIds = selectedIds + entry.card.id
                                        selecting = true
                                    }
                                    BankCardCard(
                                        item = entry.card.item,
                                        cardData = entry.card.bankCardData,
                                        isSelectionMode = selecting,
                                        isSelected = entry.card.id in selectedIds,
                                        onClick = selectCard,
                                        onLongClick = selectCard,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { rendered.get().isNotEmpty() }
    }

    private fun longPressAndCheckPosition(
        sourceKey: String,
        sourceTag: String,
        cardId: Long,
        partlyOffscreen: Boolean = false
    ) {
        val index = rendered.get().indexOfFirst { it.key == sourceKey }
        assertTrue("Missing source item $sourceKey", index >= 0)
        compose.runOnIdle { listState.requestScrollToItem(index, if (partlyOffscreen) 0 else -96) }
        compose.waitForIdle()
        if (partlyOffscreen) {
            val height = compose.onNodeWithTag(sourceTag).fetchSemanticsNode().boundsInRoot.height
            compose.runOnIdle { listState.requestScrollToItem(index, (height * 0.8f).toInt()) }
        }
        compose.waitForIdle()
        val source = compose.onNodeWithTag(sourceTag).assertIsDisplayed()
        val originalTop = source.fetchSemanticsNode().boundsInRoot.top
        source.performTouchInput { longClick() }
        compose.waitUntil(10_000) {
            rendered.get().any { it is WalletStackListEntry.SelectionHeader } &&
                rendered.get().none { it is WalletStackListEntry.Stack }
        }
        compose.waitForIdle()
        val selectedCard = compose.onNodeWithTag("wallet_card_$cardId").assertIsDisplayed()
        val selectedTop = selectedCard.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Selected card moved from $originalTop to $selectedTop", abs(originalTop - selectedTop) <= 2f)
        compose.runOnIdle { assertTrue(cardId in selectedIds) }
    }

    @Test fun longPressingADeepStackKeepsItsCurrentCoverVisible() {
        showWallet()
        longPressAndCheckPosition("stack:group-4", "wallet_stack_group-4", 300L)
    }

    @Test fun longPressingAnIndependentCardKeepsItsPositionAfterEarlierStacksExpand() {
        showWallet()
        longPressAndCheckPosition("card:1005", "wallet_card_1005", 1005L)
    }

    @Test fun selectingASearchResultWaitsForRegroupingBeforeRestoringItsPosition() {
        showWallet(search = true)
        longPressAndCheckPosition("card:300", "wallet_card_300", 300L)
    }

    @Test fun aPartlyOffscreenStackKeepsItsCoverVisibleWhenTheSelectedRowIsShorter() {
        showWallet()
        longPressAndCheckPosition("stack:group-4", "wallet_stack_group-4", 300L, partlyOffscreen = true)
    }
}
