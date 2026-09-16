package takagi.ru.monica.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.MonicaExpansionChevron

internal val DatabaseManagementPanelShape = RoundedCornerShape(24.dp)
internal val DatabaseManagementFieldShape = RoundedCornerShape(16.dp)
internal val DatabaseManagementGroupSpacing = 2.dp

@Composable
internal fun Modifier.databaseManagementClickable(
    shape: Shape = DatabaseManagementFieldShape,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = clip(shape).clickable(
    interactionSource = null,
    indication = ripple(color = MaterialTheme.colorScheme.primary),
    enabled = enabled,
    role = Role.Button,
    onClick = onClick
)

@Composable
internal fun DatabaseManagementCard(
    modifier: Modifier = Modifier,
    shape: Shape = DatabaseManagementPanelShape,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
}

@Composable
internal fun DatabaseManagementCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DatabaseManagementPanelShape,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val parentRipple = LocalRippleConfiguration.current
    val feedbackColor = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(
        LocalRippleConfiguration provides remember(feedbackColor) { RippleConfiguration(color = feedbackColor) }
    ) {
        Card(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = colors, border = border) {
            // Keep nested buttons' feedback matched to their own container colors.
            CompositionLocalProvider(LocalRippleConfiguration provides parentRipple) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseManagementTopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { ProvideTextStyle(MaterialTheme.typography.titleLarge, content = title) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
internal fun DatabaseManagementIconBadge(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Surface(shape = RoundedCornerShape(14.dp), color = containerColor, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun DatabaseManagementStatusPill(
    label: String,
    icon: ImageVector? = null,
    warning: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (warning) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (warning) colors.onErrorContainer else colors.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal data class DatabaseManagementAction(
    val icon: ImageVector,
    val title: String,
    val onClick: () -> Unit,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val warning: Boolean = false,
    val showChevron: Boolean = true,
    val busy: Boolean = false
)

@Composable
internal fun DatabaseManagementActionGroup(
    actions: List<DatabaseManagementAction>,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    if (actions.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DatabaseManagementGroupSpacing)) {
        if (title != null) {
            Text(title, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions.forEachIndexed { index, action ->
            DatabaseManagementActionRow(action, index, actions.size)
        }
    }
}

/** Also usable from lazy lists, so a long group does not need one eagerly composed card. */
@Composable
internal fun DatabaseManagementActionRow(
    action: DatabaseManagementAction,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier
) {
    val tint = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.warning -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    DatabaseManagementCard(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier.fillMaxWidth(),
        shape = settingsSectionItemShape(index, count)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(action.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = if (!action.enabled || action.warning) tint else MaterialTheme.colorScheme.onSurface)
                action.subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (action.busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (action.showChevron) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A connected pair stays outside the scrolling database grid, including its empty state. */
@Composable
internal fun DatabaseManagementCreateOpenActions(
    onCreateClick: () -> Unit,
    onOpenClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "database",
    enabled: Boolean = true
) {
    Surface(modifier = modifier.fillMaxWidth().testTag("${testTagPrefix}_source_actions"), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
            val stacked = maxWidth / LocalDensity.current.fontScale < 260.dp
            val openAction: @Composable (Modifier, Shape) -> Unit = { buttonModifier, shape ->
                FilledTonalButton(
                    onClick = onOpenClick,
                    enabled = enabled,
                    modifier = buttonModifier.heightIn(min = 56.dp).testTag("${testTagPrefix}_open_database"),
                    shape = shape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.attachment_open), textAlign = TextAlign.Center)
                }
            }
            val createAction: @Composable (Modifier, Shape) -> Unit = { buttonModifier, shape ->
                Button(
                    onClick = onCreateClick,
                    enabled = enabled,
                    modifier = buttonModifier.heightIn(min = 56.dp).testTag("${testTagPrefix}_create_database"),
                    shape = shape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.create_new), textAlign = TextAlign.Center)
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    openAction(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp))
                    createAction(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp))
                }
            } else {
                Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    openAction(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp))
                    createAction(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp))
                }
            }
        }
    }
}

/** Form submission remains reachable while fields scroll and while the keyboard is open. */
@Composable
internal fun DatabaseManagementFormActionBar(
    testTagPrefix: String = "database",
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("${testTagPrefix}_form_actions"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/** A bounded, scrollable form with its primary action outside the scrolling content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseManagementFormSheet(
    onDismiss: () -> Unit,
    testTagPrefix: String,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f)) {
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 16.dp)
                    .testTag("${testTagPrefix}_fields"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
            DatabaseManagementFormActionBar(testTagPrefix, actions)
        }
    }
}

@Composable
internal fun DatabaseManagementExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector = Icons.Default.Info,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    DatabaseManagementCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .databaseManagementClickable(shape = DatabaseManagementPanelShape) { expanded = !expanded }
                .heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            MonicaExpansionChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.mdbx_ui_collapse_details else R.string.mdbx_ui_show_details
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MonicaExpandableContent(expanded = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}
