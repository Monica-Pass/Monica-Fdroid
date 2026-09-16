package takagi.ru.monica.transfer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

internal fun TransferPhase.labelRes(): Int = when (this) {
    TransferPhase.READING -> R.string.transfer_reading
    TransferPhase.DECRYPTING -> R.string.transfer_decrypting
    TransferPhase.PREPARING -> R.string.transfer_preparing
    TransferPhase.WRITING -> R.string.transfer_writing
    TransferPhase.ATTACHMENTS -> R.string.transfer_attachments
    TransferPhase.PACKING -> R.string.transfer_packing
    TransferPhase.ENCRYPTING -> R.string.transfer_encrypting
    TransferPhase.SAVING -> R.string.transfer_saving
}

@Composable
fun TransferProgressCard(progress: TransferProgress, background: Boolean = false) {
    val fraction = progress.fraction
    val animated by animateFloatAsState(fraction ?: 0f, label = "transfer-progress")
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("transfer-progress")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(progress.phase.labelRes()), style = MaterialTheme.typography.titleLarge)
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            progress.total?.takeIf { it > 0 }?.let { total ->
                val context = LocalContext.current
                val detail = if (progress.bytes) {
                    android.text.format.Formatter.formatShortFileSize(context, progress.completed) + " / " +
                        android.text.format.Formatter.formatShortFileSize(context, total)
                } else stringResource(R.string.transfer_items_progress, progress.completed, total)
                Text(detail, style = MaterialTheme.typography.labelLarge)
            }
            if (background) Text(stringResource(R.string.transfer_background_hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
