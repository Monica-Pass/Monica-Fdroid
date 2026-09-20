package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.utils.ClipboardUtils

@Composable
internal fun SshGeneratedResult(key: SshKeyData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SshKeyResult(key, modifier) { value, sensitive ->
        ClipboardUtils.copyToClipboard(context, value, "SSH key", sensitive = sensitive)
    }
}

@Composable
internal fun SshKeyResult(
    key: SshKeyData,
    modifier: Modifier = Modifier,
    onCopy: (String, Boolean) -> Unit
) {
    var expanded by remember(key) { mutableStateOf(false) }
    Box(modifier) {
        ElevatedCard(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("ssh_result")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.generator_ssh_key), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                    }
                }
                Text(key.fingerprintSha256, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleLarge)
                Text(key.algorithm, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 360.dp)) {
            listOf(
                Triple(R.string.ssh_key_copy_public, key.publicKeyOpenSsh, false),
                Triple(R.string.ssh_key_copy_fingerprint, key.fingerprintSha256, false),
                Triple(R.string.ssh_key_copy_private, key.privateKeyOpenSsh, true)
            ).filter { it.second.isNotBlank() }.forEach { (label, value, secret) ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    text = {
                        Column {
                            Text(stringResource(label))
                            if (!secret) Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { expanded = false; onCopy(value, secret) }
                )
            }
        }
    }
}
