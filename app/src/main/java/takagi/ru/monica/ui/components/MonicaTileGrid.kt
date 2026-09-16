package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared compact tile layout. Existing vault pages retain their two-column defaults. */
@Composable
fun MonicaTileGrid(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Fixed(2),
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = columns,
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}
