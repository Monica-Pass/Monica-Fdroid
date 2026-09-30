package takagi.ru.monica.attachments.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.entryGroupShape

internal data class AttachmentEditorItem(
    val key: String,
    val title: String,
    val secondary: String,
    val onRemove: () -> Unit
)

/** Shared native attachment editor: continuous rows, one primary action and persistent feedback. */
@Composable
internal fun AttachmentEditorContent(
    items: List<AttachmentEditorItem>,
    busy: Boolean,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("attachment_editor"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (items.isEmpty()) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Icon(Icons.Default.UploadFile, null, Modifier.padding(12.dp).size(28.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(stringResource(R.string.attachment_editor_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.attachment_editor_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text(stringResource(R.string.attachments_section_title, items.size),
                modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEachIndexed { index, item -> key(item.key) {
                    Surface(shape = entryGroupShape(index, items.size), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        ListItem(
                            headlineContent = { Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = { Text(item.secondary, style = MaterialTheme.typography.bodySmall) },
                            leadingContent = {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    val icon = when (item.title.substringAfterLast('.', "").lowercase()) {
                                        "jpg", "jpeg", "png", "webp", "gif", "heic" -> Icons.Default.Image
                                        "pdf" -> Icons.Default.PictureAsPdf
                                        "zip", "7z", "rar", "tar", "gz" -> Icons.Default.FolderZip
                                        else -> Icons.Default.InsertDriveFile
                                    }
                                    Icon(icon, null, Modifier.padding(9.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = item.onRemove, enabled = !busy,
                                    modifier = Modifier.testTag("attachment_remove_${item.key}")) {
                                    Icon(Icons.Default.Close, stringResource(R.string.attachments_delete) + " " + item.title)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } }
            }
        }
        if (errorMessage != null) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(errorMessage, Modifier.weight(1f).testTag("attachment_error"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Default.Close, stringResource(R.string.close), tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        Button(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .testTag("attachment_add"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Add, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (busy) R.string.attachment_editor_working else R.string.attachments_add),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
