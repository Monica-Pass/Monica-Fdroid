package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardFaceCropper(source: Bitmap, busy: Boolean, error: Int?, onCancel: () -> Unit, onConfirm: (CardCropGeometry) -> Unit) {
    var region by remember(source) { mutableStateOf(CardCropGeometry.centered(source.width, source.height)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val frameWidth = minOf(viewport.width * .9f, viewport.height * .8f * CardFaceImageProcessor.CARD_ASPECT_RATIO)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.card_face_crop_title)) },
            navigationIcon = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
            actions = { TextButton(onClick = { onConfirm(region) }, enabled = !busy && frameWidth > 0f) {
                Text(stringResource(R.string.confirm))
            } }) },
        bottomBar = { Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            Text(stringResource(R.string.card_face_crop_hint))
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { region = CardCropGeometry.centered(source.width, source.height) }, enabled = !busy) {
                Text(stringResource(R.string.card_face_crop_reset))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }
    ) { padding ->
        Canvas(Modifier.fillMaxSize().padding(padding).testTag("card_face_crop_canvas").onSizeChanged { viewport = it }
            .pointerInput(source, frameWidth, busy) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (!busy && frameWidth > 0f) {
                        val sourcePerPixel = region.width / frameWidth
                        region = region.transform(source.width, source.height, zoom,
                            pan.x * sourcePerPixel, pan.y * sourcePerPixel)
                    }
                }
            }) {
            drawRect(Color.Black)
            if (frameWidth <= 0f) return@Canvas
            val frameHeight = frameWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
            val left = (size.width - frameWidth) / 2
            val top = (size.height - frameHeight) / 2
            val scale = frameWidth / region.width
            val imageLeft = left - region.left * scale
            val imageTop = top - region.top * scale
            drawRect(Color.White, Offset(imageLeft, imageTop), Size(source.width * scale, source.height * scale))
            drawContext.canvas.nativeCanvas.drawBitmap(source, null,
                RectF(imageLeft, imageTop, imageLeft + source.width * scale, imageTop + source.height * scale), paint)
            val radius = CornerRadius(frameHeight * .06f)
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(Rect(left, top, left + frameWidth, top + frameHeight), radius))
            }
            drawPath(mask, Color.Black.copy(alpha = .65f))
            drawRoundRect(Color.White, Offset(left, top), Size(frameWidth, frameHeight), radius, style = Stroke(2.dp.toPx()))
        }
    }
}
