package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import takagi.ru.monica.ui.components.rememberUnifiedCategoryFilterChipMenuWidth
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuMaxHeight
import androidx.compose.ui.unit.DpOffset

/** Keep Material's anchored enter/exit animation and the caller's interface scale. */
@Composable
internal fun MonicaFilterMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = UnifiedCategoryFilterChipMenuOffset,
    contentTag: String = "filter_menu_content",
    frameTag: String = "filter_menu_frame",
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val width = rememberUnifiedCategoryFilterChipMenuWidth()
    // Remain composed while closing so DropdownMenu can finish its exit transition.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
        modifier = Modifier.width(width).testTag(frameTag),
    ) {
        // Material's popup uses system density. Convert its height budget once, then let
        // the scaled content fill its measured width instead of imposing a second dp width.
        val popupDensity = LocalDensity.current
        val maxHeight = minOf(UnifiedCategoryFilterChipMenuMaxHeight,
            (LocalConfiguration.current.screenHeightDp.dp - 96.dp).coerceAtLeast(128.dp))
        val contentHeight = with(density) {
            with(popupDensity) { (maxHeight - 16.dp).toPx() }.toDp()
        }
        CompositionLocalProvider(LocalDensity provides density, LocalContext provides context,
            LocalConfiguration provides configuration) {
            // Bound the inner scrolling column; reserve the menu's own 8dp top/bottom padding.
            Column(Modifier.fillMaxWidth().heightIn(max = contentHeight)
                .testTag(contentTag)) { content() }
        }
    }
}
