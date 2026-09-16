package takagi.ru.monica.ui.vaultv2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** Small, non-scrolling grid: the overview's LazyColumn owns scrolling and module order. */
@Composable
internal fun VaultOverviewTypeGrid(
    counts: Map<VaultV2ItemType, Int>,
    onOpenType: (VaultV2ItemType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.fillMaxWidth().testTag("overview_type_grid"),
        content = {
            VaultV2ItemType.entries.forEach { type ->
                key(type) {
                    OverviewTypeTile(type, counts[type] ?: 0, onClick = { onOpenType(type) })
                }
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val minTileWidth = (144.dp * fontScale.coerceAtLeast(1f)).roundToPx()
        val columns = if (constraints.maxWidth >= minTileWidth * 2 + gap) 2 else 1
        val tileWidth = (constraints.maxWidth - gap * (columns - 1)) / columns
        // Measure the entire group before placement, so wrapping never produces uneven rows
        // or a first-frame height jump. Font, locale and width changes are measured afresh.
        val tileHeight = maxOf(92.dp.roundToPx(), measurables.maxOf { it.maxIntrinsicHeight(tileWidth) })
        val placeables = measurables.map { it.measure(Constraints.fixed(tileWidth, tileHeight)) }
        val rows = (placeables.size + columns - 1) / columns
        layout(constraints.maxWidth, constraints.constrainHeight(rows * tileHeight + (rows - 1) * gap)) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(
                    x = (index % columns) * (tileWidth + gap),
                    y = (index / columns) * (tileHeight + gap),
                )
            }
        }
    }
}

@Composable
private fun OverviewTypeTile(type: VaultV2ItemType, count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.testTag("overview_type_${type.name}"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(type.icon(), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(type.titleRes()),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineBreak = LineBreak.Heading,
                    hyphens = Hyphens.Auto,
                ),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
