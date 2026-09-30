package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** Shared field labels and keyboards; address cards keep their existing single-value storage. */
@Composable
fun EntryContactFields(emails: List<String>, phones: List<String>,
    onEmails: (List<String>) -> Unit, onPhones: (List<String>) -> Unit,
    allowMultiple: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(if (LocalTemplateFieldShape.current) 2.dp else 12.dp)) {
        ContactFields(emails, onEmails, R.string.email, R.string.add_email, KeyboardType.Email, "email", allowMultiple)
        ContactFields(phones, onPhones, R.string.phone, R.string.add_phone, KeyboardType.Phone, "phone", allowMultiple)
    }
}

@Composable
private fun ContactFields(values: List<String>, onChange: (List<String>) -> Unit,
    label: Int, addLabel: Int, keyboard: KeyboardType, tag: String, allowMultiple: Boolean) {
    values.ifEmpty { listOf("") }.forEachIndexed { index, value ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, { edited ->
                val updated = values.ifEmpty { listOf("") }.toMutableList()
                updated[index] = edited
                onChange(updated)
            }, label = { Text(stringResource(label)) }, singleLine = true, entryContentStyle = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.weight(1f).testTag("entry_contact_${tag}_$index"))
            if (allowMultiple && values.size > 1) {
                IconButton(onClick = { onChange(values.filterIndexed { i, _ -> i != index }) }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                }
            }
        }
    }
    if (allowMultiple) {
        TextButton(onClick = { onChange(values + "") }) { Text(stringResource(addLabel)) }
    }
}
