package takagi.ru.monica.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxSyncStatus

// MDBX and KeePass share the same native management primitives.
internal val MdbxPanelShape = DatabaseManagementPanelShape
internal val MdbxFieldShape = DatabaseManagementFieldShape
internal val MdbxGroupSpacing = DatabaseManagementGroupSpacing
internal typealias MdbxAction = DatabaseManagementAction

@Composable
internal fun Modifier.mdbxClickable(shape: Shape = MdbxFieldShape, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    databaseManagementClickable(shape, enabled, onClick)

@Composable
internal fun MdbxCard(modifier: Modifier = Modifier, shape: Shape = MdbxPanelShape,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border: BorderStroke? = null, content: @Composable ColumnScope.() -> Unit) =
    DatabaseManagementCard(modifier, shape, colors, border, content)

@Composable
internal fun MdbxCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MdbxPanelShape,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border: BorderStroke? = null, content: @Composable ColumnScope.() -> Unit) =
    DatabaseManagementCard(onClick, modifier, enabled, shape, colors, border, content)

@Composable
internal fun MdbxTopAppBar(title: @Composable () -> Unit, navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}) = DatabaseManagementTopAppBar(title, navigationIcon, actions)

@Composable
internal fun MdbxIconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer) = DatabaseManagementIconBadge(icon, tint, containerColor)

@Composable
internal fun MdbxStatusPill(label: String, icon: ImageVector? = null, warning: Boolean = false) =
    DatabaseManagementStatusPill(label, icon, warning)

@Composable
internal fun MdbxActionGroup(actions: List<MdbxAction>, modifier: Modifier = Modifier, title: String? = null) =
    DatabaseManagementActionGroup(actions, modifier, title)

@Composable
internal fun MdbxActionRow(action: MdbxAction, index: Int, count: Int, modifier: Modifier = Modifier) =
    DatabaseManagementActionRow(action, index, count, modifier)

@Composable
internal fun MdbxCreateOpenActions(onCreateClick: () -> Unit, onOpenClick: () -> Unit, modifier: Modifier = Modifier) =
    DatabaseManagementCreateOpenActions(onCreateClick, onOpenClick, modifier, testTagPrefix = "mdbx")

@Composable
internal fun MdbxFormActionBar(content: @Composable ColumnScope.() -> Unit) =
    DatabaseManagementFormActionBar(testTagPrefix = "mdbx", content = content)

@Composable
internal fun MdbxExpandableSection(title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    icon: ImageVector = Icons.Default.Info, content: @Composable ColumnScope.() -> Unit) =
    DatabaseManagementExpandableSection(title, modifier, subtitle, icon, content = content)

@StringRes
internal fun mdbxSyncStatusLabel(status: String): Int = when (status) {
    MdbxSyncStatus.LOCAL_ONLY.name -> R.string.keepass_remote_sync_status_local_only
    MdbxSyncStatus.IN_SYNC.name -> R.string.keepass_remote_sync_status_in_sync
    MdbxSyncStatus.SYNCING.name -> R.string.keepass_remote_sync_status_syncing
    MdbxSyncStatus.PENDING_UPLOAD.name -> R.string.keepass_remote_sync_status_pending_upload
    MdbxSyncStatus.REMOTE_CHANGED.name -> R.string.keepass_remote_sync_status_remote_changed
    MdbxSyncStatus.CONFLICT.name -> R.string.keepass_remote_sync_status_conflict
    MdbxSyncStatus.FAILED.name -> R.string.keepass_remote_sync_status_failed
    else -> R.string.mdbx_ui_details_unavailable
}
