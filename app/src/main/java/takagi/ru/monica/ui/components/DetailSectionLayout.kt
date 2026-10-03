package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Section labels sit outside the connected reading surfaces, matching the editor. */
@Composable
internal fun DetailSectionLayout(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
internal fun DetailGroupItem(index: Int, count: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = entryGroupShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (LocalCompactCredentialFields.current) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
