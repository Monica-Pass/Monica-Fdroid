package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

internal val LocalStandaloneTemplateFields = staticCompositionLocalOf { false }
internal val LocalTemplateFieldShape = staticCompositionLocalOf { false }

/** Connected filled fields; outer corners belong to the whole group. */
@Composable
internal fun TemplateFormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Text(title, modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompositionLocalProvider(LocalFilledEntryForm provides true, LocalTemplateFieldShape provides true) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}
