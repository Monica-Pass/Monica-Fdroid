package takagi.ru.monica.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.OneDriveAccountSession

/**
 * Shared OneDrive context for backup and database flows.
 * Account, connection state and location stay together so feature-specific
 * options can begin only after the user understands where they are working.
 */
@Composable
internal fun OneDriveLocationPanel(
    session: OneDriveAccountSession?,
    isConnecting: Boolean,
    accountActionLabel: String,
    connectionLabel: String,
    connectionFailed: Boolean,
    errorMessage: String?,
    onAccountAction: () -> Unit,
    browserTitle: String,
    currentPath: String,
    isLoadingEntries: Boolean,
    entries: List<FileSourceEntry>,
    emptyMessage: String,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (() -> Unit)? = null,
    entryEnabled: (FileSourceEntry) -> Boolean = { true },
    entrySelected: (FileSourceEntry) -> Boolean = { false },
    entryIcon: (FileSourceEntry) -> ImageVector = { Icons.Default.Folder },
    entrySupportingText: @Composable (FileSourceEntry) -> String? = { null },
    onEntryClick: (FileSourceEntry) -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OneDriveAccountRow(
                session = session,
                isConnecting = isConnecting,
                actionLabel = accountActionLabel,
                connectionLabel = connectionLabel,
                connectionFailed = connectionFailed,
                onAction = onAccountAction
            )

            errorMessage?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            AnimatedVisibility(
                visible = session != null,
                enter = fadeIn() + expandVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 360.dp
                        if (compact) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OneDrivePathIdentity(
                                    browserTitle = browserTitle,
                                    currentPath = currentPath,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OneDriveBrowserActions(
                                    currentPath = currentPath,
                                    isLoadingEntries = isLoadingEntries,
                                    onNavigateUp = onNavigateUp,
                                    onRefresh = onRefresh,
                                    onCreateFolder = onCreateFolder,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OneDrivePathIdentity(
                                    browserTitle = browserTitle,
                                    currentPath = currentPath,
                                    modifier = Modifier.weight(1f)
                                )
                                OneDriveBrowserActions(
                                    currentPath = currentPath,
                                    isLoadingEntries = isLoadingEntries,
                                    onNavigateUp = onNavigateUp,
                                    onRefresh = onRefresh,
                                    onCreateFolder = onCreateFolder
                                )
                            }
                        }
                    }

                    OneDriveEntryList(
                        entries = entries,
                        isLoading = isLoadingEntries,
                        emptyMessage = emptyMessage,
                        entryEnabled = entryEnabled,
                        entrySelected = entrySelected,
                        entryIcon = entryIcon,
                        entrySupportingText = entrySupportingText,
                        onEntryClick = onEntryClick
                    )

                    footer()
                }
            }
        }
    }
}

@Composable
private fun OneDriveAccountRow(
    session: OneDriveAccountSession?,
    isConnecting: Boolean,
    actionLabel: String,
    connectionLabel: String,
    connectionFailed: Boolean,
    onAction: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OneDriveAccountIdentity(
                    session = session,
                    connectionLabel = connectionLabel,
                    connectionFailed = connectionFailed,
                    modifier = Modifier.fillMaxWidth()
                )
                OneDriveAccountAction(
                    session = session,
                    isConnecting = isConnecting,
                    actionLabel = actionLabel,
                    onAction = onAction,
                    modifier = if (session == null) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.align(Alignment.End)
                    }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OneDriveAccountIdentity(
                    session = session,
                    connectionLabel = connectionLabel,
                    connectionFailed = connectionFailed,
                    modifier = Modifier.weight(1f)
                )
                OneDriveAccountAction(
                    session = session,
                    isConnecting = isConnecting,
                    actionLabel = actionLabel,
                    onAction = onAction
                )
            }
        }
    }
}

@Composable
private fun OneDriveAccountIdentity(
    session: OneDriveAccountSession?,
    connectionLabel: String,
    connectionFailed: Boolean,
    modifier: Modifier = Modifier
) {
    val title = session?.displayName?.ifBlank { session.username } ?: "OneDrive"
    val accountName = session?.username?.takeIf { it.isNotBlank() && it != title }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp).size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            accountName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = when {
                        connectionFailed -> Icons.Default.Error
                        session != null -> Icons.Default.CheckCircle
                        else -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = when {
                        connectionFailed -> MaterialTheme.colorScheme.error
                        session != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = connectionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        connectionFailed -> MaterialTheme.colorScheme.error
                        session != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun OneDriveAccountAction(
    session: OneDriveAccountSession?,
    isConnecting: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (session == null) {
        Button(
            onClick = onAction,
            enabled = !isConnecting,
            modifier = modifier.heightIn(min = 48.dp)
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(actionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        TextButton(
            onClick = onAction,
            enabled = !isConnecting,
            modifier = modifier.heightIn(min = 48.dp)
        ) {
            Text(actionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OneDrivePathIdentity(
    browserTitle: String,
    currentPath: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = browserTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = currentPath.toOneDriveDisplayPath(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OneDriveBrowserActions(
    currentPath: String,
    isLoadingEntries: Boolean,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onNavigateUp,
            enabled = currentPath.isNotBlank() && !isLoadingEntries
        ) {
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = stringResource(R.string.onedrive_parent_folder)
            )
        }
        IconButton(onClick = onRefresh, enabled = !isLoadingEntries) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.onedrive_refresh_folder)
            )
        }
        onCreateFolder?.let { createFolder ->
            IconButton(onClick = createFolder, enabled = !isLoadingEntries) {
                Icon(
                    Icons.Default.CreateNewFolder,
                    contentDescription = stringResource(R.string.onedrive_create_folder)
                )
            }
        }
    }
}

@Composable
private fun OneDriveEntryList(
    entries: List<FileSourceEntry>,
    isLoading: Boolean,
    emptyMessage: String,
    entryEnabled: (FileSourceEntry) -> Boolean,
    entrySelected: (FileSourceEntry) -> Boolean,
    entryIcon: (FileSourceEntry) -> ImageVector,
    entrySupportingText: @Composable (FileSourceEntry) -> String?,
    onEntryClick: (FileSourceEntry) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            entries.isEmpty() -> Text(
                text = emptyMessage,
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(entries, key = { "${it.path}:${it.isDirectory}" }) { entry ->
                    val enabled = entryEnabled(entry)
                    val selected = entrySelected(entry)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onEntryClick(entry) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = entryIcon(entry),
                            contentDescription = null,
                            tint = when {
                                selected -> MaterialTheme.colorScheme.primary
                                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.name,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = when {
                                    selected -> MaterialTheme.colorScheme.primary
                                    enabled -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            entrySupportingText(entry)?.let { supporting ->
                                Text(
                                    text = supporting,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle
                            else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 50.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

internal fun String.toOneDriveDisplayPath(): String =
    trim('/').takeIf { it.isNotBlank() }?.let { "/$it" } ?: "/"
