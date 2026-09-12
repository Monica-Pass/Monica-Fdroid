package takagi.ru.monica.bitwarden.service

import androidx.annotation.StringRes
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import takagi.ru.monica.R

internal enum class BitwardenLoginError(@param:StringRes val messageRes: Int) {
    CertificateExpired(R.string.bitwarden_error_certificate_expired),
    CertificateNotYetValid(R.string.bitwarden_error_certificate_not_yet_valid),
    CertificateHostnameMismatch(R.string.bitwarden_error_certificate_hostname),
    CertificateUntrusted(R.string.bitwarden_error_certificate_untrusted),
    TlsHandshake(R.string.bitwarden_error_tls_handshake),
    Dns(R.string.bitwarden_error_dns),
    Timeout(R.string.bitwarden_error_timeout),
    Connection(R.string.bitwarden_error_connection),
    Proxy(R.string.bitwarden_error_proxy),
    CleartextBlocked(R.string.bitwarden_error_cleartext),
    NetworkIo(R.string.bitwarden_error_network_io),
}

/** Keep certificate failures ahead of generic SSL/IO failures, including wrapped causes. */
internal fun classifyBitwardenLoginError(error: Throwable): BitwardenLoginError? {
    val causes = buildList<Throwable> {
        var current: Throwable? = error
        while (current != null && size < 32 && none { it === current }) {
            add(current)
            current = current.cause
        }
    }
    val messages = causes.joinToString("\n") { it.message.orEmpty().take(4096) }
        .lowercase(Locale.ROOT)
    val hasIoFailure = causes.any { it is IOException }
    val hasTlsFailure = causes.any {
        it is SSLException || it is CertificateException || it is CertPathValidatorException
    }
    fun containsAny(vararg phrases: String) = phrases.any(messages::contains)
    fun certificateReason(reason: CertPathValidatorException.BasicReason) = causes.any {
        it is CertPathValidatorException && it.reason == reason
    }

    return when {
        causes.any { it is CertificateExpiredException } ||
            certificateReason(CertPathValidatorException.BasicReason.EXPIRED) ||
            hasTlsFailure && containsAny("certificate_expired", "certificate has expired", "certificate expired") ->
            BitwardenLoginError.CertificateExpired

        causes.any { it is CertificateNotYetValidException } ||
            certificateReason(CertPathValidatorException.BasicReason.NOT_YET_VALID) ||
            hasTlsFailure && containsAny("certificate_not_yet_valid", "certificate not yet valid", "certificate is not yet valid") ->
            BitwardenLoginError.CertificateNotYetValid

        hasTlsFailure && (
            containsAny("hostname") && containsAny("not verified", "mismatch", "does not match") ||
                containsAny("no subject alternative", "no name matching")
            ) ->
            BitwardenLoginError.CertificateHostnameMismatch

        causes.any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException } ||
            hasTlsFailure && containsAny("trust anchor", "certpathvalidatorexception", "certificate verify failed",
                "certificate_verify_failed", "cert_authority_invalid", "unable to find valid certification path",
                "certificate pinning failure", "self signed certificate", "self-signed certificate") ->
            BitwardenLoginError.CertificateUntrusted

        hasIoFailure && containsAny("failed to authenticate with proxy", "proxy authentication required", "http_proxy_auth",
            "unexpected response code for connect: 407") -> BitwardenLoginError.Proxy

        hasIoFailure && containsAny("cleartext communication", "cleartext traffic") &&
            containsAny("not permitted", "not allowed") -> BitwardenLoginError.CleartextBlocked

        causes.any { it is UnknownHostException } ||
            hasIoFailure && containsAny("unable to resolve host", "unknownhostexception", "eai_nodata", "eai_noname") ->
            BitwardenLoginError.Dns

        causes.any { it is SocketTimeoutException } ||
            causes.any { it is InterruptedIOException && it.message?.contains("timeout", ignoreCase = true) == true } ||
            hasIoFailure && causes.none { it is ConnectException } &&
                Regex("(?:^|\\s)(?:timed out|timeout)(?:$|[\\s:(])").containsMatchIn(messages) ->
            BitwardenLoginError.Timeout

        causes.any { it is SSLException } ->
            BitwardenLoginError.TlsHandshake

        causes.any { it is ConnectException || it is NoRouteToHostException } ||
            hasIoFailure && containsAny("failed to connect", "connection refused", "network is unreachable", "no route to host") ->
            BitwardenLoginError.Connection

        hasIoFailure -> BitwardenLoginError.NetworkIo
        else -> null // Keep existing account, verification and server-response messages.
    }
}
