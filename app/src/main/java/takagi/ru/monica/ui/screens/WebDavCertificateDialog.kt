package takagi.ru.monica.ui.screens

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
        title = { Text("WebDAV 证书不受信任") },
        text = {
            Column {
                Text("服务器：${certificate.host}")
                Spacer(Modifier.height(6.dp))
                Text("SHA-256：${certificate.fingerprint}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text("继续后仅信任当前证书；证书变化时会再次确认。")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                WebDavCertificateTrustStore.trust(certificate.host, certificate.fingerprint)
                onContinue()
            }) { Text("继续连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

fun Throwable.findWebDavCertificateError(): WebDavUntrustedCertificateException? =
    generateSequence(this) { it.cause }
        .filterIsInstance<WebDavUntrustedCertificateException>()
        .firstOrNull()
