package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import takagi.ru.monica.R
import takagi.ru.monica.attachments.EmbeddedWalletAccess
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.ui.cardwallet.CardFaceArtwork
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor
import takagi.ru.monica.ui.cardwallet.bankCardFacePreviewData
import takagi.ru.monica.ui.screens.BankCardDetailScreen
import takagi.ru.monica.ui.screens.NoteDetailScreen

@Composable
fun EmbeddedWalletPreview(snapshot: EmbeddedWalletContent.Snapshot, bitmap: Bitmap? = null,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = GroupedItemDefaults.SingleShape
    val interaction = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    if (snapshot.kind == EmbeddedWalletContent.Kind.BANK_CARD) {
        val data = remember(snapshot.encode()) { CardWalletDataCodec.parseBankCardData(snapshot.itemData.toString()) }
        if (data != null) CardFaceArtwork(bankCardFacePreviewData(snapshot.title, data), bitmap,
            displayMode = data.cardFace?.displayMode ?: CardFaceDisplayMode.ALL,
            modifier = modifier.testTag("password_payment_face").fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO).clip(shape).then(interaction))
    } else if (snapshot.kind == EmbeddedWalletContent.Kind.DOCUMENT) {
        val data = remember(snapshot.encode()) { takagi.ru.monica.data.model.EmbeddedDocumentEditorData(snapshot).data }
        CardFaceArtwork(takagi.ru.monica.ui.cardwallet.documentCardFacePreviewData(snapshot.title, data), bitmap,
            displayMode = data.cardFace?.displayMode ?: CardFaceDisplayMode.ALL,
            modifier = modifier.testTag("password_document_face").fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO).clip(shape).then(interaction))
    } else if (snapshot.kind == EmbeddedWalletContent.Kind.ADDRESS) {
        val data = remember(snapshot.encode()) { CardWalletDataCodec.parseBillingAddressData(snapshot.itemData.toString()) }
        if (data != null) CardFaceArtwork(takagi.ru.monica.ui.cardwallet.billingAddressCardFacePreviewData(snapshot.title, data), bitmap,
            displayMode = data.cardFace?.displayMode ?: CardFaceDisplayMode.ALL,
            modifier = modifier.testTag("password_address_face").fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO).clip(shape).then(interaction))
    } else ListItem(headlineContent = { Text(snapshot.title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.embedded_copy_independent, snapshot.assets.size)) },
        leadingContent = { Icon(Icons.Default.Description, null) },
        modifier = modifier.clip(shape).then(interaction),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
}

@Composable
fun EmbeddedWalletSavedContent(snapshot: EmbeddedWalletContent.Snapshot, entry: PasswordEntry? = null,
    showPreview: Boolean = true, initiallyOpen: Boolean = false, onClose: () -> Unit = {},
    nativeAccess: EmbeddedWalletAccess? = null) {
    val context = LocalContext.current
    var access by remember(snapshot.encode(), entry?.id, nativeAccess) { mutableStateOf(nativeAccess) }
    var bitmap by remember(snapshot.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(snapshot.id) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var open by remember(snapshot.id) { mutableStateOf(initiallyOpen) }
    val close = { open = false; onClose() }
    LaunchedEffect(snapshot.encode(), entry, nativeAccess, attempt) {
        var owned: EmbeddedWalletAccess? = null
        try {
            val current = nativeAccess ?: EmbeddedWalletAccess.open(context, requireNotNull(entry), snapshot).also { owned = it }
            access = current
            snapshot.assets.firstOrNull { it.role == EmbeddedWalletContent.AssetRole.CARD_FACE }?.let { bitmap = current.image(it.name) }
            error = false
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = true
        } finally { access = null; owned?.close() }
    }
    if (showPreview) EmbeddedWalletPreview(snapshot, bitmap) { open = true }
    val database = remember(context) { takagi.ru.monica.data.PasswordDatabase.getDatabase(context) }
    val security = takagi.ru.monica.ui.rememberUiSecurityManager()
    val repository = remember(database, security) { takagi.ru.monica.repository.SecureItemRepository(database.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext) }
    if (open && access != null) Dialog(onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().testTag("embedded_wallet_detail")) { when (snapshot.kind) {
            EmbeddedWalletContent.Kind.BANK_CARD -> BankCardDetailScreen(androidx.lifecycle.viewmodel.compose.viewModel {
                takagi.ru.monica.viewmodel.BankCardViewModel(repository, context, database.localKeePassDatabaseDao(), security,
                    strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
            }, 0,
                onNavigateBack = close, onEditCard = {}, embeddedAccess = access)
            EmbeddedWalletContent.Kind.NOTE -> NoteDetailScreen(androidx.lifecycle.viewmodel.compose.viewModel {
                takagi.ru.monica.viewmodel.NoteViewModel(repository, context = context, localKeePassDatabaseDao = database.localKeePassDatabaseDao(), securityManager = security,
                    strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
            }, 0,
                onNavigateBack = close, onEditNote = {}, onCreateSend = { _, _ -> }, embeddedAccess = access)
            EmbeddedWalletContent.Kind.ADDRESS -> takagi.ru.monica.ui.screens.BillingAddressDetailScreen(
                androidx.lifecycle.viewmodel.compose.viewModel {
                    takagi.ru.monica.viewmodel.BillingAddressViewModel(repository, security, context)
                }, 0, onNavigateBack = close, onEditAddress = {}, embeddedAccess = access)
            EmbeddedWalletContent.Kind.DOCUMENT -> takagi.ru.monica.ui.screens.DocumentDetailScreen(
                androidx.lifecycle.viewmodel.compose.viewModel {
                    takagi.ru.monica.viewmodel.DocumentViewModel(repository, context, database.localKeePassDatabaseDao(), security,
                        strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
                }, 0, onNavigateBack = close, onEditDocument = {}, embeddedAccess = access)
        } }
    }
    if (open && error) AlertDialog(onDismissRequest = close,
        title = { Text(stringResource(R.string.embedded_copy_failed)) },
        text = { Text(stringResource(R.string.embedded_copy_failed_message, snapshot.title)) },
        confirmButton = { TextButton(onClick = { error = false; attempt++ }) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
}

/** Use parent-owned artwork in the existing wallet browser without creating a wallet record. */
@Composable
internal fun EmbeddedWalletStackFace(snapshot: EmbeddedWalletContent.Snapshot, entry: PasswordEntry, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, snapshot.encode(), entry) {
        val asset = snapshot.assets.firstOrNull { it.role == EmbeddedWalletContent.AssetRole.CARD_FACE }
            ?: return@produceState
        var access: EmbeddedWalletAccess? = null
        try {
            access = EmbeddedWalletAccess.open(context, entry, snapshot)
            value = access.image(asset.name)
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { value = null
        } finally { access?.close() }
    }
    EmbeddedWalletPreview(snapshot, bitmap, modifier)
}
