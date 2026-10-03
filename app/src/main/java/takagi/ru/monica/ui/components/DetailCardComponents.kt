package takagi.ru.monica.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.icons.MonicaIcons

internal val LocalCompactCredentialFields = staticCompositionLocalOf { false }

/** One surface per information section; embedded fields do not add another card. */
@Composable
fun DetailCardSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}

/** Shared reading and action layout for native and custom fields. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailField(
    label: String,
    value: String,
    context: Context,
    displayValue: String = value,
    protected: Boolean = false,
    visible: Boolean = true,
    onToggleVisibility: (() -> Unit)? = null,
    onCreateSend: ((String, String) -> Unit)? = null,
    onCopy: () -> Unit = { copyPasswordDetailFieldValue(context, label, value) },
    showDescription: String = stringResource(R.string.show),
    hideDescription: String = stringResource(R.string.hide),
) {
    val menu = rememberPasswordFieldActionMenuState()
    BoxWithConstraints(Modifier.fillMaxWidth().animateMonicaContentSize()) {
        if (LocalCompactCredentialFields.current) {
            val interactions = remember { MutableInteractionSource() }
            var press by remember { mutableStateOf<Offset?>(null) }
            LaunchedEffect(interactions) {
                interactions.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> press = interaction.pressPosition
                        is PressInteraction.Cancel -> press = null
                    }
                }
            }
            PasswordFieldActionMenuHost(menu, label, value, displayValue, context,
                includeVisibilityToggle = protected, isVisible = visible,
                onToggleVisibility = onToggleVisibility, onCreateSend = onCreateSend, onCopy = onCopy)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable(interactionSource = interactions, indication = LocalIndication.current) {
                    menu.openAt(press); press = null
                }.testTag("compact_credential_field"), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (protected) Icons.Default.Key else Icons.Default.AlternateEmail, null,
                    Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(displayValue, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        fontFamily = if (protected) FontFamily.Monospace else null,
                        maxLines = if (protected && !visible) 1 else Int.MAX_VALUE)
                }
                if (protected && onToggleVisibility != null) IconButton(onClick = onToggleVisibility) {
                    Icon(if (visible) MonicaIcons.Security.visibilityOff else MonicaIcons.Security.visibility,
                        if (visible) hideDescription else showDescription, Modifier.size(20.dp))
                }
                IconButton(onClick = onCopy) {
                    Icon(MonicaIcons.Action.copy, stringResource(R.string.copy), Modifier.size(20.dp))
                }
            }
            return@BoxWithConstraints
        }
        val separateActions = maxWidth < 240.dp || LocalDensity.current.fontScale > 1.25f
        val actions: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                if (protected && onToggleVisibility != null) {
                    IconButton(onClick = onToggleVisibility, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (visible) MonicaIcons.Security.visibilityOff else MonicaIcons.Security.visibility,
                            if (visible) hideDescription else showDescription,
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                    Icon(MonicaIcons.Action.copy, stringResource(R.string.copy),
                        Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PasswordFieldActionMenuButton(
                    state = menu, label = label, value = value, displayValue = displayValue,
                    context = context, includeVisibilityToggle = protected, isVisible = visible,
                    onToggleVisibility = onToggleVisibility, onCreateSend = onCreateSend,
                    onCopy = onCopy, modifier = Modifier.size(48.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!separateActions) actions()
            }
            Text(
                text = displayValue,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    .clickable { menu.open() }.padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                fontFamily = if (protected && !visible) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (protected && !visible) 1 else Int.MAX_VALUE,
            )
            if (separateActions) Box(Modifier.align(Alignment.End)) { actions() }
        }
    }
}
