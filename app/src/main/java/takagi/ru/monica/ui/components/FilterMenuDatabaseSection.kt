package takagi.ru.monica.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@Composable
internal fun FilterMenuSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "filter_section_arrow")
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(12.dp))
        .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (onToggle != null) Icon(Icons.Default.KeyboardArrowDown, null,
            Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> FilterMenuDatabaseSection(
    items: List<DatabaseFilterChipItem<T>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val showExpanded = !collapsible || expanded
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var width by remember { mutableStateOf(0) }
    Column(modifier.fillMaxWidth().onSizeChanged { width = it.width },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterMenuSectionHeader(stringResource(R.string.category_selection_menu_databases),
            showExpanded, if (collapsible) ({ expanded = !expanded }) else null,
            Modifier.testTag(if (collapsible) "database_expand_toggle" else "database_static_header"))
        // Retain virtualization for unusually large database lists.
        if (items.size > 32) {
            DatabaseFilterChipContent(items, showExpanded, isSelected, onSelect,
                availableWidth = width.takeIf { it > 0 }?.let { with(density) { it.toDp() } })
        } else {
            val chip: @Composable (DatabaseFilterChipItem<T>) -> Unit = { item ->
                MonicaExpressiveFilterChip(isSelected(item.value), { onSelect(item.value) },
                    item.label, leadingIcon = item.icon, statusDotColor = item.statusDotColor,
                    modifier = Modifier.testTag("database_filter_" + item.key))
            }
            // Keep one animated viewport when replacing the single row with
            // wrapped rows. The large-list path already owns its size animation.
            Box(Modifier.fillMaxWidth().testTag("database_chip_viewport")
                .animateContentSize(tween(220, easing = FastOutSlowInEasing))) {
                if (showExpanded) FlowRow(Modifier.fillMaxWidth().testTag("database_expanded"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)) {
                    items.forEach { key(it.key) { chip(it) } }
                } else Row(Modifier.fillMaxWidth().horizontalScroll(scroll).testTag("database_row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)) {
                    items.forEach { key(it.key) { chip(it) } }
                }
            }
        }
    }
}
