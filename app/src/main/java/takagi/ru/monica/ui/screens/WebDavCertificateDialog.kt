package takagi.ru.monica.ui.screens

import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import takagi.ru.monica.webdav.WebDavCertificateTrustStore
import takagi.ru.monica.webdav.WebDavUntrustedCertificateException

@Composable
fun WebDavCertificateDialog(
    certificate: WebDavUntrustedCertificateException,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.legacy_ui_webdav_untrusted_certificate)) },
        text = {
            Column {
                Text(stringResource(R.string.legacy_ui_certificate_host, certificate.host))
                Spacer(Modifier.height(6.dp))
                Text("SHA-256：${certificate.fingerprint}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.legacy_ui_certificate_trust_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                WebDavCertificateTrustStore.trust(certificate.host, certificate.fingerprint)
                onContinue()
            }) { Text(stringResource(R.string.legacy_ui_continue_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

fun Throwable.findWebDavCertificateError(): WebDavUntrustedCertificateException? =
    generateSequence(this) { it.cause }
        .filterIsInstance<WebDavUntrustedCertificateException>()
        .firstOrNull()
