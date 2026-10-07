package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** Presentation only. Authentication and deletion remain with the caller. */
@Composable
internal fun ClearDataSheetContent(selected: List<Boolean>, onSelection: (Int, Boolean) -> Unit,
    password: String, onPassword: (String) -> Unit, verificationRequired: Boolean,
    onCancel: () -> Unit, onConfirm: () -> Unit, verifying: Boolean = false) {
    val labels = listOf(R.string.data_type_passwords, R.string.data_type_totp, R.string.data_type_notes,
        R.string.data_type_documents, R.string.data_type_bank_cards, R.string.data_type_generator_history)
    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .86f)
        .imePadding().padding(horizontal = 12.dp).testTag("clear_data_sheet")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DeleteForever, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.clear_all_data), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.clear_all_data_warning), Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsPanelGroup(stringResource(R.string.select_data_types_to_clear)) {
                labels.forEachIndexed { index, label ->
                    Row(Modifier.settingsPanelSurface().testTag("clear_data_type_$index")
                        .toggleable(selected[index], enabled = !verifying, role = Role.Checkbox) { onSelection(index, it) }
                        .heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Checkbox(selected[index], onCheckedChange = null)
                    }
                }
            }
            if (verificationRequired) androidx.compose.material3.OutlinedTextField(password, onPassword,
                label = { Text(stringResource(R.string.enter_master_password_to_confirm)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !verifying,
                modifier = Modifier.fillMaxWidth().testTag("clear_data_password"))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("clear_data_actions"),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onCancel, Modifier.weight(1f), enabled = !verifying) { Text(stringResource(R.string.cancel)) }
            Button(onConfirm, Modifier.weight(1f).testTag("clear_data_confirm"),
                enabled = !verifying && selected.any { it } && (!verificationRequired || password.isNotEmpty()),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(if (verifying) R.string.clear_data_verifying else R.string.confirm))
            }
        }
    }
}
