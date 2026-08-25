package takagi.ru.monica.ui.vaultv2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.common.layout.DetailPane
import takagi.ru.monica.ui.common.layout.ListPane

internal enum class VaultV2DetailKind {
    PASSWORD,
    AUTHENTICATOR,
    BANK_CARD,
    DOCUMENT,
    BILLING_ADDRESS,
    NOTE,
    PASSKEY
}

@Composable
internal fun VaultV2TabPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    hasWideDetail: Boolean,
    onClearWideDetail: () -> Unit,
    listContent: @Composable () -> Unit,
    detailContent: @Composable BoxScope.() -> Unit
) {
    if (isCompactWidth) {
        ListPane(modifier = Modifier.fillMaxSize()) {
            listContent()
        }
        return
    }

    BackHandler(enabled = hasWideDetail, onBack = onClearWideDetail)
    Row(modifier = Modifier.fillMaxSize()) {
        ListPane(
            modifier = Modifier
                .fillMaxHeight()
                .width(wideListPaneWidth)
        ) {
            listContent()
        }
        DetailPane(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            VaultV2DetailPaneContent(
                hasWideDetail = hasWideDetail,
                detailContent = detailContent
            )
        }
    }
}

@Composable
internal fun VaultV2DetailPaneContent(
    hasWideDetail: Boolean,
    detailContent: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (hasWideDetail) {
            detailContent()
        } else {
            Text(
                text = stringResource(R.string.select_item_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
