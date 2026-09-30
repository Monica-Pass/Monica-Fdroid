package takagi.ru.monica.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.*
import takagi.ru.monica.utils.*

internal fun WifiSecurity.labelRes(): Int = when (this) {
    WifiSecurity.NONE -> R.string.wifi_security_none
    WifiSecurity.WEP -> R.string.wifi_security_wep
    WifiSecurity.WPA_WPA2 -> R.string.wifi_security_wpa_wpa2
    WifiSecurity.WPA2_WPA3 -> R.string.wifi_security_wpa2_wpa3
    WifiSecurity.WPA3 -> R.string.wifi_security_wpa3
    WifiSecurity.WPA2_ENTERPRISE -> R.string.wifi_security_wpa2_enterprise
    WifiSecurity.WPA3_ENTERPRISE -> R.string.wifi_security_wpa3_enterprise
}

@Composable
internal fun WifiDetailContent(entry: PasswordEntry, password: String?) {
    val context = LocalContext.current
    val details = remember(entry.wifiMetadata, entry.title) { WifiEntryDetails.from(entry) }
    val wifi = details.qrData()
    val canUseSecret = password != null || details.security == WifiSecurity.NONE
    val payload = remember(details, password) { if (canUseSecret && wifi != null) WifiQrPayload.build(wifi, password.orEmpty()) else null }
    var showQr by remember(entry.id, payload) { mutableStateOf(false) }
    var menu by remember(entry.id) { mutableStateOf(false) }
    val canConnect = details.readable && details.ssid.isNotBlank() && canUseSecret
    val connect: () -> Unit = {
        // Unknown types can still be configured in system settings, but are not encoded as a QR code.
        if (canConnect && WifiConnectLauncher.launch(context,
                wifi ?: WifiData(ssid = details.ssid), password.orEmpty()) == WifiConnectLauncher.Result.Failed) {
            Toast.makeText(context, R.string.wifi_connect_failed, Toast.LENGTH_LONG).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Surface(onClick = { menu = true }, shape = entryGroupShape(0, if (details.hidden) 3 else 2),
                color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("wifi_detail_ssid")) {
                ListItem(headlineContent = { Text(details.ssid) }, supportingContent = { Text(stringResource(R.string.wifi_ssid_label)) },
                    leadingContent = { Icon(Icons.Default.Wifi, null) }, trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = {
                                    ClipboardUtils.copyToClipboard(context, details.ssid, context.getString(R.string.wifi_ssid_label)); menu = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.wifi_connect_button)) }, enabled = canConnect,
                                    onClick = { menu = false; connect() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.wifi_qr_button)) }, enabled = payload != null,
                                    onClick = { menu = false; showQr = true })
                            }
                        }
                    }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            }
            Surface(shape = entryGroupShape(1, if (details.hidden) 3 else 2), color = MaterialTheme.colorScheme.surfaceContainer) {
                ListItem(headlineContent = { Text(details.security?.let { stringResource(it.labelRes()) } ?: details.securityName.ifBlank { stringResource(R.string.content_block_unavailable) }) },
                    supportingContent = { Text(stringResource(R.string.wifi_security_label)) }, leadingContent = { Icon(Icons.Default.Shield, null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            }
            if (details.hidden) Surface(shape = entryGroupShape(2, 3), color = MaterialTheme.colorScheme.surfaceContainer) {
                ListItem(headlineContent = { Text(stringResource(R.string.wifi_hidden_network)) }, leadingContent = { Icon(Icons.Default.VisibilityOff, null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = connect, enabled = canConnect, modifier = Modifier.weight(1f).testTag("wifi_detail_connect")) {
                Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_connect_button))
            }
            FilledTonalButton(onClick = { showQr = true }, enabled = payload != null, modifier = Modifier.weight(1f).testTag("wifi_detail_qr")) {
                Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_qr_button))
            }
        }
        if (details.security in setOf(WifiSecurity.WPA2_ENTERPRISE, WifiSecurity.WPA3_ENTERPRISE)) {
            Text(stringResource(R.string.wifi_qr_unsupported), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showQr && payload != null) TextQrCodeDialog(stringResource(R.string.wifi_qr_dialog_title, details.ssid), payload, onDismiss = { showQr = false })
}
