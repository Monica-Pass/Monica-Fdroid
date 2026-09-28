package takagi.ru.monica.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun InfoField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
fun InfoFieldWithCopy(
    label: String,
    value: String,
    copyValue: String = value,
    context: Context,
    onCreateSend: ((title: String, text: String) -> Unit)? = null,
) {
    DetailField(label = label, value = copyValue, displayValue = value, context = context, onCreateSend = onCreateSend)
}

@Composable
fun PasswordField(
    label: String,
    value: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    context: Context,
    onCreateSend: ((title: String, text: String) -> Unit)? = null,
    maskedValue: String = "••••••••",
) {
    DetailField(label = label, value = value, displayValue = if (visible) value else maskedValue,
        context = context, protected = true, visible = visible, onToggleVisibility = onToggleVisibility,
        onCreateSend = onCreateSend)
}
