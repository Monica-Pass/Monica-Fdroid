package takagi.ru.monica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.utils.SettingsManager

val LocalEntryContentStyle = staticCompositionLocalOf { false }
val LocalEntryFieldMotion = staticCompositionLocalOf { false }

@Composable
fun rememberEntryContentStyle(): Boolean = LocalEntryContentStyle.current

@Composable
fun EntryContentStylePicker(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.entry_editor_style), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp))
        listOf(false, true).forEach { onDemand ->
            val shape = RoundedCornerShape(
                topStart = if (!onDemand) 24.dp else 4.dp, topEnd = if (!onDemand) 24.dp else 4.dp,
                bottomStart = if (onDemand) 24.dp else 4.dp, bottomEnd = if (onDemand) 24.dp else 4.dp)
            ListItem(
                headlineContent = { Text(stringResource(if (onDemand) R.string.entry_editor_on_demand else R.string.entry_editor_classic)) },
                supportingContent = { Text(stringResource(if (onDemand) R.string.entry_editor_on_demand_description else R.string.entry_editor_classic_description)) },
                leadingContent = { RadioButton(selected = enabled == onDemand, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().clip(shape).testTag("entry_style_$onDemand")
                    .selectable(selected = enabled == onDemand, role = Role.RadioButton, onClick = { onChange(onDemand) }),
            )
        }
        Text(stringResource(R.string.entry_editor_style_retention), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryContentFieldButton(fields: List<CustomFieldDraft>, onFieldsChange: (List<CustomFieldDraft>) -> Unit) =
    EntryContentFieldButton(fields, onFieldsChange, Modifier)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryContentFieldButton(fields: List<CustomFieldDraft>, onFieldsChange: (List<CustomFieldDraft>) -> Unit, modifier: Modifier) {
    var showMenu by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { showMenu = true }, modifier = modifier.testTag("entry_content_add")) {
        Icon(Icons.Default.Add, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.password_content_add))
    }
    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.password_content_add),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
                val choices = listOf(R.string.entry_field_text, R.string.entry_field_hidden,
                    R.string.email, R.string.phone, R.string.website)
                choices.forEachIndexed { index, titleRes ->
                    val title = stringResource(titleRes)
                    ListItem(headlineContent = { Text(title) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().clip(entryGroupShape(index, choices.size))
                            .testTag("entry_field_kind_$index")
                            .clickable(role = Role.Button) {
                                onFieldsChange(fields + CustomFieldDraft(
                                    id = CustomFieldDraft.nextTempId(fields.map { it.id }),
                                    title = if (index < 2) "" else title, value = "", isProtected = index == 1))
                                showMenu = false
                            })
                }
            }
        }
    }
}

val EntryFieldDraftSaver = androidx.compose.runtime.saveable.listSaver<List<CustomFieldDraft>, Any>(
    save = { fields -> fields.flatMap { listOf(it.id, it.title, it.value, it.isProtected, it.isPreset, it.isRequired, it.presetId.orEmpty(), it.placeholder, it.secureFieldType?.name.orEmpty()) } },
    restore = { values -> values.chunked(9).map {
        CustomFieldDraft(id = it[0] as Long, title = it[1] as String, value = it[2] as String, isProtected = it[3] as Boolean,
            isPreset = it[4] as Boolean, isRequired = it[5] as Boolean,
            presetId = (it[6] as String).ifEmpty { null }, placeholder = it[7] as String,
            secureFieldType = (it[8] as String).takeIf(String::isNotEmpty)?.let { name ->
                takagi.ru.monica.data.model.SecureCustomFieldType.valueOf(name)
            })
    } },
)

fun entryGroupShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) 24.dp else 4.dp,
    topEnd = if (index == 0) 24.dp else 4.dp,
    bottomStart = if (index == count - 1) 24.dp else 4.dp,
    bottomEnd = if (index == count - 1) 24.dp else 4.dp,
)
