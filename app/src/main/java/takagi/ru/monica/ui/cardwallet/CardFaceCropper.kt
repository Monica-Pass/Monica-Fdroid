package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.style.TextOverflow
import takagi.ru.monica.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardFaceCropper(
    source: Bitmap, busy: Boolean, error: Int?, onCancel: () -> Unit,
    onConfirm: (CardCropGeometry) -> Unit,
    onSkipCrop: (() -> Unit)? = null,
) {
    var region by remember(source) { mutableStateOf(CardCropGeometry.centered(source.width, source.height)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val frameWidth = minOf(viewport.width * .9f, viewport.height * .8f * CardFaceImageProcessor.CARD_ASPECT_RATIO)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(if (onSkipCrop != null) R.string.photo_crop_title else R.string.card_face_crop_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("card_face_crop_cancel")) {
                Icon(Icons.Default.Close, stringResource(R.string.cancel))
            } },
            ) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                        val shape = RoundedCornerShape(16.dp)
                        FilledTonalIconButton(onClick = { region = region.rotated(source.width, source.height, false) },
                            enabled = !busy, shape = shape,
                            modifier = Modifier.size(48.dp).testTag("card_face_rotate_left")) {
                            Icon(Icons.Default.RotateLeft, stringResource(R.string.card_face_rotate_left))
                        }
                        FilledTonalIconButton(onClick = { region = region.rotated(source.width, source.height, true) },
                            enabled = !busy, shape = shape,
                            modifier = Modifier.size(48.dp).testTag("card_face_rotate_right")) {
                            Icon(Icons.Default.RotateRight, stringResource(R.string.card_face_rotate_right))
                        }
                        FilledTonalIconToggleButton(checked = region.flipHorizontal,
                            onCheckedChange = { region = region.flipped(source.width, source.height, true) },
                            enabled = !busy, shape = shape,
                            modifier = Modifier.size(48.dp).testTag("card_face_flip_horizontal")) {
                            Icon(Icons.Default.Flip, stringResource(R.string.card_face_flip_horizontal))
                        }
                        FilledTonalIconToggleButton(checked = region.flipVertical,
                            onCheckedChange = { region = region.flipped(source.width, source.height, false) },
                            enabled = !busy, shape = shape,
                            modifier = Modifier.size(48.dp).testTag("card_face_flip_vertical")) {
                            Icon(Icons.Default.Flip, stringResource(R.string.card_face_flip_vertical), Modifier.rotate(90f))
                        }
                        FilledTonalIconButton(onClick = { region = CardCropGeometry.centered(source.width, source.height) },
                            enabled = !busy, shape = shape,
                            modifier = Modifier.size(48.dp).testTag("card_face_crop_reset")) {
                            Icon(Icons.Default.RestartAlt, stringResource(R.string.card_face_crop_reset))
                        }
                    }
                    if (LocalConfiguration.current.screenHeightDp > 400) {
                        Text(stringResource(R.string.card_face_crop_rotation_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    val stackedActions = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
                    @Composable fun SkipAction(modifier: Modifier) {
                        if (onSkipCrop != null) FilledTonalButton(onClick = onSkipCrop, enabled = !busy,
                            modifier = modifier.heightIn(min = 48.dp).testTag("photo_crop_skip")) {
                            Text(stringResource(R.string.photo_crop_skip))
                        }
                    }
                    @Composable fun ConfirmAction(modifier: Modifier) {
                        Button(onClick = { onConfirm(region) }, enabled = !busy && frameWidth > 0f,
                            modifier = modifier.heightIn(min = 48.dp).testTag("card_face_crop_confirm")) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (onSkipCrop != null) R.string.photo_crop_continue else R.string.card_face_crop_apply))
                        }
                    }
                    if (stackedActions) {
                        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkipAction(Modifier.fillMaxWidth())
                            ConfirmAction(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkipAction(Modifier.weight(1f))
                            ConfirmAction(Modifier.weight(1f))
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

    ) { padding ->
        Canvas(Modifier.fillMaxSize().padding(padding)
            // Clip after the Scaffold insets so transformed images cannot cover the bars.
            .clipToBounds()
            .testTag("card_face_crop_canvas").onSizeChanged { viewport = it }
            .pointerInput(source, frameWidth, busy, region.quarterTurns, region.flipHorizontal, region.flipVertical) {
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
            CardFaceImageProcessor.drawCropSource(drawContext.canvas.nativeCanvas, source, region,
                left, top, frameWidth, paint)
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
