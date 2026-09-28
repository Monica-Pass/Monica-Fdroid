package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val BarcodePreviewCardPadding = 18.dp
internal val BarcodePreviewPaperPadding = 12.dp

/** Shared by saved barcode entries and codes generated from detail fields. */
@Composable
internal fun BarcodePreviewCard(
    bitmap: Bitmap?,
    contentDescription: String,
    imageModifier: Modifier,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(BarcodePreviewCardPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Box(
                    // The scan surface stays rectangular, inset from the card's rounded corners.
                    modifier = imageModifier
                        .background(Color.White)
                        .semantics { this.contentDescription = contentDescription }
                        .padding(BarcodePreviewPaperPadding),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                }
            } else {
                placeholder()
            }
        }
    }
}
