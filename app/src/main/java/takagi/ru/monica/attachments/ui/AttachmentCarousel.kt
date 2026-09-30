package takagi.ru.monica.attachments.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState

/** Local image bytes are decoded in memory only. A carousel never downloads remote previews. */
@Composable
internal fun AttachmentCarousel(
    attachments: List<Attachment>,
    displayNames: Map<String, String>,
    readThumbnail: suspend (Attachment) -> ByteArray?,
    onOpen: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val state = rememberCarouselState { attachments.size }
    val current = state.currentItem.coerceIn(attachments.indices)
    val selected = attachments[current]
    Column(modifier.fillMaxWidth().testTag("attachment_carousel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.attachments_section_title, attachments.size), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${current + 1} / ${attachments.size}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalMultiBrowseCarousel(
            state = state, preferredItemWidth = 260.dp,
            modifier = Modifier.fillMaxWidth().height(216.dp).testTag("attachment_carousel_track"), itemSpacing = 8.dp,
            minSmallItemWidth = 48.dp, maxSmallItemWidth = 72.dp,
        ) { index ->
            val attachment = attachments[index]
            key(attachment.id, attachment.localPath, attachment.downloadState) {
                val name = displayNames[attachment.fileName] ?: attachment.fileName
                var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(attachment.id, attachment.localPath, attachment.downloadState) {
                    if (attachment.downloadStateEnum == AttachmentDownloadState.DOWNLOADED && attachment.mimeType.startsWith("image/")) {
                        try {
                            thumbnail = withContext(Dispatchers.IO) {
                                val bytes = readThumbnail(attachment) ?: return@withContext null
                                try {
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                    var sample = 1
                                    while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                        BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                                } finally { bytes.fill(0) }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Opening the original still offers the normal error/retry path. */ }
                    }
                }
                val downloading = attachment.downloadStateEnum == AttachmentDownloadState.DOWNLOADING
                val failed = attachment.downloadStateEnum == AttachmentDownloadState.FAILED
                val pending = attachment.downloadStateEnum == AttachmentDownloadState.PENDING
                val action = when {
                    failed -> stringResource(R.string.attachment_retry)
                    pending -> stringResource(R.string.attachment_download)
                    else -> name
                }
                Box(Modifier.fillMaxSize().maskClip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .testTag("attachment_tile_${attachment.id}")
                    .semantics { contentDescription = if (action == name) name else "$name · $action" }
                    .clickable(enabled = !downloading, role = Role.Button) { onOpen(attachment) },
                    contentAlignment = Alignment.Center) {
                    thumbnail?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (thumbnail == null) {
                        Icon(when {
                            attachment.mimeType.startsWith("image/") -> Icons.Default.Image
                            attachment.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
                            else -> Icons.Default.Description
                        }, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (downloading || failed || pending) {
                        Surface(Modifier.align(Alignment.BottomEnd).padding(16.dp), shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                if (downloading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(if (failed) Icons.Default.Refresh else Icons.Default.CloudDownload, null)
                            }
                        }
                    }
                }
            }
        }
        // Keep the selected name outside the mask, readable even with large fonts and narrow tiles.
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(displayNames[selected.fileName] ?: selected.fileName, style = MaterialTheme.typography.bodyMedium)
            Text(formatSecondary(selected), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
