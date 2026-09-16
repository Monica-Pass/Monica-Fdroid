package takagi.ru.monica.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DatabaseFilterChipItem<T>(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val value: T,
    val statusDotColor: Color? = null,
)

/** The menu only composes visible chips. Expanding replaces, rather than duplicates, the row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> DatabaseFilterChipContent(
    items: List<DatabaseFilterChipItem<T>>,
    expanded: Boolean,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    // DropdownMenu's intrinsic width pass also asks Columns for intrinsic heights. Neither query
    // may reach LazyLayout, whose off-screen children deliberately do not exist.
    val width = (rememberUnifiedCategoryFilterChipMenuWidth() - 32.dp).coerceAtLeast(1.dp)
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.labelLarge
    val menuHeight = minOf(460.dp, (LocalConfiguration.current.screenHeightDp.dp - 96.dp).coerceAtLeast(48.dp))
    val headerHeight = with(density) {
        textStyle.lineHeight.takeIf { it.isSpecified }?.toDp() ?: 20.sp.toDp()
    }.coerceAtLeast(24.dp) + 4.dp
    // Leave room for the header, its gap, the menu's 16dp padding and DropdownMenu's 8dp padding.
    // Otherwise the inner list's final row could end up beneath the outer popup's clipping edge.
    val bodyHeight = (menuHeight - headerHeight - 8.dp - 48.dp).coerceAtLeast(48.dp)
    val viewport = Modifier.width(width).then(DatabaseChipViewportIntrinsics(width, if (expanded) bodyHeight else 48.dp))
    val chip: @Composable (DatabaseFilterChipItem<T>) -> Unit = { item ->
        MonicaExpressiveFilterChip(
            selected = isSelected(item.value),
            onClick = { onSelect(item.value) },
            label = item.label,
            leadingIcon = item.icon,
            statusDotColor = item.statusDotColor,
            // Selecting a database closes the popup. Five per-chip animation controllers have
            // no visible selection transition here; retain the chip's bounded press indication.
            animated = false,
            modifier = Modifier.widthIn(max = width),
        )
    }
    Box(modifier = viewport.animateContentSize(tween(180)).clipToBounds()) {
        if (!expanded) {
            LazyRow(modifier = viewport, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.key }) { chip(it) }
            }
        } else if (items.size <= EagerDatabaseChipLimit) {
            FlowRow(
                modifier = viewport,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { item -> key(item.key) { chip(item) } }
            }
        } else {
            // Keep the user's position while updated names/status markers are regrouped.
            val listState = rememberLazyListState()
            val direction = LocalLayoutDirection.current
            val fontResolver = LocalFontFamilyResolver.current
            val typeface = fontResolver.resolve(
                textStyle.fontFamily,
                textStyle.fontWeight ?: FontWeight.Normal,
                textStyle.fontStyle ?: FontStyle.Normal,
                textStyle.fontSynthesis ?: FontSynthesis.All,
            ).value
            val padding = FilterChipDefaults.ContentPadding
            val elementSpacing = FilterChipDefaults.HorizontalSpacing
            val availableWidth = with(density) { width.roundToPx().coerceAtLeast(1) }
            // Material's chip retains a zero-width trailing slot: Row measures two internal gaps.
            // Status dots are part of the label, and Icons without a size modifier are 24dp.
            val iconAndPaddingWidth = with(density) {
                24.dp.roundToPx() + 2 * elementSpacing.roundToPx() +
                    padding.calculateLeftPadding(direction).roundToPx() +
                    padding.calculateRightPadding(direction).roundToPx()
            }
            val statusWidth = with(density) { 7.dp.roundToPx() + 6.dp.roundToPx() }
            val spacing = with(density) { 8.dp.roundToPx() }
            val minimumWidth = with(density) { 48.dp.roundToPx() }
            // Only display metadata is retained. Changing selection does not reshape every label.
            val labels = remember(items) { items.map { it.label to (it.statusDotColor != null) } }
            var rowEnds by remember(labels, width, density, direction, fontResolver, textStyle, typeface) {
                mutableStateOf<IntArray?>(null)
            }
            LaunchedEffect(labels, width, density, direction, fontResolver, textStyle, typeface) {
                rowEnds = withContext(Dispatchers.Default) {
                    // Do not share TextMeasurer's mutable cache with the UI thread.
                    val measurer = TextMeasurer(fontResolver, density, direction, cacheSize = 0)
                    val widths = IntArray(labels.size) { index ->
                        ensureActive()
                        val (label, hasStatus) = labels[index]
                        val extra = iconAndPaddingWidth + if (hasStatus) statusWidth else 0
                        val measured = measurer.measure(
                            text = AnnotatedString(label),
                            style = textStyle,
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = Constraints(maxWidth = (availableWidth - extra).coerceAtLeast(0)),
                        ).size.width
                        (measured + extra).coerceIn(minimumWidth.coerceAtMost(availableWidth), availableWidth)
                    }
                    databaseFilterChipRowEnds(widths, availableWidth, spacing)
                }
            }
            val rows = rowEnds
            if (rows == null) {
                // Render the first viewport immediately while remaining labels are measured off-thread.
                FlowRow(
                    modifier = viewport.heightIn(max = bodyHeight),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.take(EagerDatabaseChipLimit).forEach { item -> key(item.key) { chip(item) } }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = viewport.heightIn(max = bodyHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = rows.size, key = { row -> items[if (row == 0) 0 else rows[row - 1]].key }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val start = if (row == 0) 0 else rows[row - 1]
                            for (index in start until rows[row]) {
                                val item = items[index]
                                key(item.key) { chip(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val EagerDatabaseChipLimit = 32

/** Estimates for the dropdown's sizing probe; real measurement still determines content height. */
private data class DatabaseChipViewportIntrinsics(val width: Dp, val height: Dp) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = width.roundToPx()
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = width.roundToPx()
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = height.roundToPx()
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = height.roundToPx()
}
