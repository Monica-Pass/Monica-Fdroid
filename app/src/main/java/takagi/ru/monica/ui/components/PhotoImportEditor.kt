package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.ui.cardwallet.CardFaceCropper
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor
import takagi.ru.monica.util.ImageManager

/** Shared by camera and gallery imports for both sides of bank cards and documents. */
@Composable
internal fun PhotoImportEditor(
    imageManager: ImageManager,
    bitmap: Bitmap,
    originalSizeBytes: Long?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember(bitmap) { mutableStateOf<Bitmap?>(null) }
    var processing by remember(bitmap) { mutableStateOf(false) }
    var error by remember(bitmap) { mutableStateOf<Int?>(null) }
    val busy = processing || isSaving
    val ready = selected
    if (ready == null) {
        Dialog(onDismissRequest = { if (!busy) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
            Surface(Modifier.fillMaxSize()) {
                CardFaceCropper(bitmap, busy, error,
                    onCancel = { if (!busy) onDismiss() },
                    onSkipCrop = { if (!busy) selected = bitmap },
                    onConfirm = { region ->
                        if (!busy) {
                            processing = true
                            error = null
                            scope.launch {
                                try {
                                    CardFaceImageProcessor.cropPhoto(bitmap, region)
                                        .onSuccess { selected = it }
                                        .onFailure { error = R.string.photo_save_failed }
                                } finally { processing = false }
                            }
                        }
                    })
            }
        }
    } else {
        ImageImportConfirmDialog(imageManager, ready, originalSizeBytes, isSaving,
            // Back from quality confirmation returns to the original crop, without writing a file.
            onDismiss = { if (!isSaving) selected = null },
            onConfirm = { quality -> if (!isSaving) onConfirm(ready, quality) })
    }
}
