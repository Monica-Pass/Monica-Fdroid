package takagi.ru.monica.bitwarden.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.viewmodel.ParsedTotpItem

@Composable
internal fun BitwardenTotpPickerDialog(
    suggestions: List<ParsedTotpItem>,
    onCodeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    currentSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    var query by remember { mutableStateOf("") }
    var generationFailed by remember { mutableStateOf(false) }
    val choices = suggestions.filter { it.totpData.otpType == OtpType.TOTP && it.totpData.secret.isNotBlank() }
        .filter { query.isBlank() || it.item.title.contains(query, true) ||
            it.totpData.issuer.contains(query, true) || it.totpData.accountName.contains(query, true) }
    AlertDialog(onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp).testTag("bitwarden_totp_picker"),
        title = { Text(stringResource(R.string.legacy_ui_totp_from_monica)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bitwarden_authenticator_search)) },
                    modifier = Modifier.fillMaxWidth().testTag("bitwarden_totp_search"))
                Text(stringResource(R.string.bitwarden_totp_picker_hint), style = MaterialTheme.typography.bodySmall)
                if (generationFailed) Text(stringResource(R.string.bitwarden_totp_generation_failed),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (choices.isEmpty()) Text(stringResource(R.string.legacy_ui_totp_from_monica_empty))
                    choices.forEach { parsed ->
                        FilledTonalButton(onClick = {
                            // Generate at selection time; a list held open across a period must not reuse an old code.
                            val code = runCatching { TotpGenerator.generateOtp(parsed.totpData, currentSeconds = currentSeconds()) }.getOrNull()
                            if (code.isNullOrBlank()) generationFailed = true else onCodeSelected(code)
                        }, modifier = Modifier.fillMaxWidth().testTag("bitwarden_totp_${parsed.item.id}")) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(parsed.item.title, style = MaterialTheme.typography.titleSmall)
                                val subtitle = listOf(parsed.totpData.issuer, parsed.totpData.accountName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
