package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.data.model.PasswordQrTemplate

/** Display-only: format selection and resolved secrets never enter the saved content envelope. */
@Composable
internal fun PasswordQrBarcodePreview(block: PasswordContentBlocks.Block) {
    val context = LocalContext.current
    val readValues by rememberUpdatedState(LocalQrTemplateActions.current?.read)
    var value by remember(block.raw) { mutableStateOf<String?>(null) }
    var resolutionError by remember(block.raw) { mutableStateOf<String?>(null) }
    var format by rememberSaveable(block.id) { mutableStateOf(FieldBarcodeFormat.QR_CODE) }
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val availableWidth = with(density) {
        (widthPx - 2 * BarcodePreviewCardPadding.roundToPx() -
            2 * BarcodePreviewPaperPadding.roundToPx()).coerceAtLeast(0)
    }
    var rendered by remember(value, format, availableWidth) { mutableStateOf(FieldBarcodeRender(loading = true)) }
    LaunchedEffect(block.raw) {
        try {
            val values = if (PasswordQrTemplate.isTemplate(block)) requireNotNull(readValues).invoke()
                else PasswordQrTemplate.Values(emptyMap())
            value = withContext(Dispatchers.Default) { PasswordQrTemplate.resolve(block, values) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            resolutionError = if (cause is PasswordQrTemplate.Invalid)
                context.getString(R.string.qr_template_field_error, cause.field)
            else context.getString(R.string.qr_template_error)
        }
    }
    LaunchedEffect(value, format, availableWidth) {
        val content = value ?: return@LaunchedEffect
        if (availableWidth > 0) rendered = withContext(Dispatchers.Default) {
            encodeFieldBarcode(content, format, availableWidth)
        }
    }

    val scope = rememberCoroutineScope()
    var exportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    // The system picker also works on Android 8/9 without broad storage permissions.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val snapshot = exportBitmap
        exportBitmap = null
        if (uri != null) scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(snapshot)
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                        check(snapshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                Toast.makeText(context, R.string.common_account_saved, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { saveError = true
            } finally { saving = false }
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FieldBarcodeFormat.entries.forEachIndexed { index, option ->
                SegmentedButton(selected = format == option, onClick = { format = option },
                    shape = SegmentedButtonDefaults.itemShape(index, FieldBarcodeFormat.entries.size),
                    modifier = Modifier.testTag("qr_content_format_${option.name}")) {
                    Text(stringResource(option.labelRes))
                }
            }
        }
        val bitmap = rendered.bitmap
        val imageModifier = if (format == FieldBarcodeFormat.QR_CODE)
            Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
        else Modifier.fillMaxWidth().height(with(density) {
            ((bitmap?.height ?: 360) + 2 * BarcodePreviewPaperPadding.roundToPx()).toDp()
        })
        BarcodePreviewCard(bitmap = bitmap,
            contentDescription = stringResource(format.labelRes), imageModifier = imageModifier,
            modifier = Modifier.testTag("qr_content_preview").onSizeChanged { widthPx = it.width }) {
            val error = resolutionError
            if (error == null && rendered.loading) {
                Box(imageModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else Text(error ?: stringResource(when {
                rendered.needsWiderScreen -> R.string.field_barcode_linear_too_wide
                format == FieldBarcodeFormat.CODE_128 -> R.string.field_barcode_linear_failed
                else -> R.string.content_block_qr_error
            }), modifier = Modifier.padding(16.dp).testTag("qr_content_error"),
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        FilledTonalButton(onClick = {
            exportBitmap = bitmap
            saveError = false
            exporter.launch(if (format == FieldBarcodeFormat.QR_CODE) "Monica_QR.png" else "Monica_Barcode.png")
        }, enabled = bitmap != null && !saving, modifier = Modifier.align(Alignment.End).testTag("qr_content_save_image")) {
            Icon(Icons.Default.SaveAlt, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.save))
        }
        if (saveError) Text(stringResource(R.string.content_block_save_error), color = MaterialTheme.colorScheme.error)
    }
}
