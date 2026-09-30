package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.PermissionInfo
import takagi.ru.monica.data.model.PermissionStatus
import takagi.ru.monica.ui.screens.PermissionClickAction
import takagi.ru.monica.ui.screens.resolvePermissionClickAction

@Composable
fun PermissionCard(
    permission: PermissionInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
) {
    val action = resolvePermissionClickAction(permission.id, permission.status)
    val permissionName = stringResource(permission.nameResId)
    val statusText = stringResource(when (permission.status) {
        PermissionStatus.GRANTED -> R.string.permission_status_granted
        PermissionStatus.DENIED -> R.string.permission_status_denied
        PermissionStatus.UNAVAILABLE -> R.string.permission_status_unavailable
        PermissionStatus.UNKNOWN -> R.string.permission_status_unknown
    })
    val statusIcon = when (permission.status) {
        PermissionStatus.GRANTED -> Icons.Default.CheckCircle
        PermissionStatus.DENIED -> Icons.Default.RadioButtonUnchecked
        PermissionStatus.UNAVAILABLE -> Icons.Default.Block
        PermissionStatus.UNKNOWN -> Icons.Default.HelpOutline
    }
    // An ungranted optional permission is a normal state, not a full-card error.
    val statusColor = if (permission.status == PermissionStatus.GRANTED) {
        MaterialTheme.colorScheme.primary
    } else MaterialTheme.colorScheme.onSurfaceVariant
    val actionLabel = when (action) {
        PermissionClickAction.IGNORE, PermissionClickAction.SHOW_GRANTED -> null
        PermissionClickAction.REQUEST_RUNTIME_PERMISSION -> stringResource(R.string.permission_action_request)
        else -> stringResource(R.string.permission_open_system_settings)
    }
    val interaction = if (actionLabel != null) Modifier
        .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
    else Modifier
    Surface(modifier.fillMaxWidth().testTag("permission_row_${permission.id}")
        .clip(shape).then(interaction).semantics(mergeDescendants = true) { stateDescription = statusText },
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(permission.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(permissionName, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(permission.descriptionResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Icon(statusIcon, null, Modifier.size(18.dp).testTag("permission_status_${permission.id}"), tint = statusColor)
        }
    }
}
