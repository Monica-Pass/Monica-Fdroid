package takagi.ru.monica.ui.vaultv2

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.LocalAnimatedVisibilityScope
import takagi.ru.monica.ui.cardwallet.*

/** Only the small visible deck is retained, and the vault's security cleanup clears it. */
internal class VaultOverviewCardStackState {
    var expanded by mutableStateOf(false)
    var animateEntrance by mutableStateOf(false)
    var hasOpenedDetail by mutableStateOf(false)
    var coverRevealed by mutableStateOf(false)
    var originBounds by mutableStateOf<Rect?>(null)
    var prepared by mutableStateOf<VaultOverviewWalletCards?>(null, referentialEqualityPolicy())

    fun clear() {
        expanded = false
        animateEntrance = false
        hasOpenedDetail = false
        coverRevealed = false
        originBounds = null
        prepared = null
    }
}

internal class VaultOverviewWalletCards(
    val scope: String,
    val input: List<VaultV2Item>,
    val cards: List<WalletListItem>,
) {
    val itemsById = input.mapNotNull { item -> item.secureItem?.id?.let { it to item } }.toMap()

    fun entry(selectedKey: String?): WalletStackListEntry.Stack? {
        if (cards.isEmpty()) return null
        val selectedId = cards.firstOrNull { itemsById[it.id]?.overviewIdentity() == selectedKey }?.id
            ?: cards.first().id
        return WalletStackListEntry.Stack(WalletStack("overview:$scope", cards.map { it.id }, selectedId), cards)
    }
}

@Composable
internal fun rememberOverviewWalletCards(
    items: List<VaultV2Item>,
    scope: String,
    securityManager: SecurityManager,
    state: VaultOverviewCardStackState,
): VaultOverviewWalletCards? {
    LaunchedEffect(items, scope, securityManager) {
        if (state.prepared?.let { it.scope == scope && it.input == items } == true) return@LaunchedEffect
        if (state.prepared?.scope != scope) state.clear()
        val previousOrder = state.prepared?.cards?.map { it.id }.orEmpty().takeIf { state.expanded }.orEmpty()
        val prepared = withContext(Dispatchers.Default) {
            val decrypt: (String) -> String = securityManager::decryptDataIfMonicaCiphertext
            val cards = items.mapNotNull { item ->
                val secure = item.secureItem ?: return@mapNotNull null
                when (item.type) {
                    VaultV2ItemType.BANK_CARD -> WalletListItem(secure.id, WalletListItemType.BANK_CARD, secure,
                        bankCardData = CardWalletDataCodec.parseBankCardData(secure.itemData, decrypt))
                    VaultV2ItemType.DOCUMENT -> WalletListItem(secure.id, WalletListItemType.DOCUMENT, secure,
                        documentData = CardWalletDataCodec.parseDocumentData(secure.itemData, decrypt))
                    VaultV2ItemType.BILLING_ADDRESS -> WalletListItem(secure.id, WalletListItemType.BILLING_ADDRESS, secure,
                        billingAddressData = CardWalletDataCodec.parseBillingAddressData(secure.itemData, decrypt))
                    else -> null
                }
            }
            // Opening a detail updates usage ranking. Keep an open deck in its browsing order.
            val ordered = if (previousOrder.isEmpty()) cards else {
                val byId = cards.associateBy { it.id }
                previousOrder.mapNotNull(byId::get) + cards.filterNot { it.id in previousOrder }
            }
            VaultOverviewWalletCards(scope, items, ordered)
        }
        state.prepared = prepared
        if (prepared.cards.isEmpty()) state.expanded = false
    }
    // A changed scope or locked/removed source must never expose the previous deck while preparing.
    return state.prepared?.takeIf { it.scope == scope && it.input == items }
}

@Composable
internal fun OverviewCards(
    prepared: VaultOverviewWalletCards?,
    selectedCardKey: String?,
    listState: LazyListState,
    state: VaultOverviewCardStackState,
    isDetailVisible: Boolean,
    onManage: () -> Unit,
) {
    val entry = prepared?.entry(selectedCardKey)
    val coroutineScope = rememberCoroutineScope()
    val navigation = LocalAnimatedVisibilityScope.current?.transition
    val canMeasureOrigin = !isDetailVisible && (navigation == null ||
        !navigation.isRunning && navigation.currentState == EnterExitState.Visible &&
            navigation.targetState == EnterExitState.Visible)
    if (entry == null) {
        Surface(Modifier.fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {}
        return
    }
    Column(Modifier.testTag("overview_card_deck")) {
        WalletStackCard(
            entry = entry,
            onClick = {
                coroutineScope.launch {
                    listState.stopScroll()
                    state.animateEntrance = true
                    state.hasOpenedDetail = false
                    state.coverRevealed = false
                    state.expanded = true
                }
            },
            onLongClick = onManage,
            onManage = onManage,
            // Window coordinates include the page's navigation transform. Keep the
            // last settled cover bounds while detail navigation moves the page.
            onCoverBounds = { if (canMeasureOrigin) state.originBounds = it },
            coverVisible = !state.expanded || state.coverRevealed || isDetailVisible && state.hasOpenedDetail,
            controlsVisible = !state.expanded || isDetailVisible && state.hasOpenedDetail,
        )
    }
}

@Composable
internal fun OverviewCardStackBrowser(
    prepared: VaultOverviewWalletCards?,
    sources: Map<String, VaultOverviewSource>,
    selectedCardKey: String?,
    state: VaultOverviewCardStackState,
    isDetailVisible: Boolean,
    reduceAnimations: Boolean,
    onSelectedCardChange: (String) -> Unit,
    onOpenItem: (VaultV2Item) -> Unit,
    onManage: () -> Unit,
) {
    val entry = prepared?.entry(selectedCardKey) ?: return
    if (!state.expanded || isDetailVisible && state.hasOpenedDetail) return
    fun select(id: Long) { prepared.itemsById[id]?.let { onSelectedCardChange(it.overviewIdentity()) } }
    WalletStackBrowser(
        entry = entry,
        originBounds = state.originBounds,
        initialCardId = entry.cover.id,
        animateEntrance = state.animateEntrance,
        onOpened = { state.animateEntrance = false },
        onFocusedCardChanged = ::select,
        onCollapseStart = ::select,
        onRevealCover = { state.coverRevealed = true },
        onDismiss = { state.expanded = false; state.coverRevealed = false },
        onOpenCard = { card ->
            prepared.itemsById[card.id]?.let { item ->
                select(card.id)
                state.animateEntrance = false
                state.hasOpenedDetail = true
                onOpenItem(item)
            }
        },
        onManage = onManage,
        title = stringResource(R.string.vault_overview_cards),
        reduceAnimations = reduceAnimations,
        sourceName = { card -> sources[prepared.itemsById[card.id]?.overviewSource()]?.name.takeIf { prepared.scope == "all" } },
    )
}
