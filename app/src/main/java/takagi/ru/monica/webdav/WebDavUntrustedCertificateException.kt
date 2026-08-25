package takagi.ru.monica.webdav

import javax.net.ssl.SSLHandshakeException

class WebDavUntrustedCertificateException(
    val host: String,
    val fingerprint: String,
    cause: Throwable
) : SSLHandshakeException(
    "WebDAV certificate is not trusted for $host (SHA-256: $fingerprint)"
) {
    init { initCause(cause) }
}
