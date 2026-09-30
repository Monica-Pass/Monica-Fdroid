package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.ui.cardwallet.*
import takagi.ru.monica.utils.SettingsManager

/** Adapt embedded copies to the existing wallet cover/browser; never persist synthetic wallet IDs. */
@Composable
internal fun DetailWalletStack(
    snapshots: List<EmbeddedWalletContent.Snapshot>, entry: PasswordEntry, title: String,
) {
    if (snapshots.isEmpty()) return
    if (snapshots.size == 1) { EmbeddedWalletSavedContent(snapshots.single(), entry); return }
    val cards = remember(snapshots) { snapshots.mapIndexed { index, snapshot ->
        val item = snapshot.displayItem().copy(id = index.toLong() + 1)
        when (snapshot.kind) {
            EmbeddedWalletContent.Kind.BANK_CARD -> WalletListItem(item.id, WalletListItemType.BANK_CARD, item,
                bankCardData = CardWalletDataCodec.parseBankCardData(item.itemData))
            EmbeddedWalletContent.Kind.DOCUMENT -> WalletListItem(item.id, WalletListItemType.DOCUMENT, item,
                documentData = takagi.ru.monica.data.model.EmbeddedDocumentEditorData(snapshot).data)
            else -> WalletListItem(item.id, WalletListItemType.BILLING_ADDRESS, item,
                billingAddressData = CardWalletDataCodec.parseBillingAddressData(item.itemData))
        }
    } }
    var focusedId by rememberSaveable(entry.id) { mutableLongStateOf(cards.first().id) }
    var open by rememberSaveable(entry.id) { mutableStateOf(false) }
    var selected by remember(entry.id) { mutableStateOf<EmbeddedWalletContent.Snapshot?>(null) }
    var origin by remember { mutableStateOf<Rect?>(null) }
    var coverVisible by remember { mutableStateOf(true) }
    val stack = WalletStackListEntry.Stack(WalletStack("embedded:${entry.id}", cards.map { it.id }, focusedId), cards)
    val face: @Composable (WalletListItem, Modifier) -> Unit = { card, modifier ->
        EmbeddedWalletStackFace(snapshots[(card.id - 1).toInt()], entry, modifier)
    }
    val context = LocalContext.current
    val settings by remember(context) { SettingsManager(context) }.settingsFlow.collectAsState(initial = null)
    Box(Modifier.testTag("detail_wallet_stack")) {
        WalletStackCard(stack, onClick = { open = true }, onLongClick = { open = true }, onManage = null,
            onCoverBounds = { origin = it }, coverVisible = coverVisible,
            modifier = Modifier.testTag("detail_wallet_cover"), cardFace = face)
    }
    if (open) Dialog(onDismissRequest = { open = false; coverVisible = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        WalletStackOverlayHost {
            WalletStackBrowser(stack, originBounds = origin, initialCardId = focusedId, animateEntrance = true,
                onOpened = { coverVisible = false }, onFocusedCardChanged = { focusedId = it },
                onCollapseStart = { focusedId = it }, onRevealCover = { coverVisible = true },
                onDismiss = { open = false; coverVisible = true },
                onOpenCard = { selected = snapshots[(it.id - 1).toInt()] }, onManage = null,
                title = title, reduceAnimations = settings?.reduceAnimations ?: false,
                loopEnabled = settings?.walletStackLoopEnabled ?: false, cardFace = face)
        }
        selected?.let { snapshot -> key(snapshot.id) {
            EmbeddedWalletSavedContent(snapshot, entry, showPreview = false, initiallyOpen = true,
                onClose = { selected = null })
        } }
    }
}
