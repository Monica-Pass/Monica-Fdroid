package takagi.ru.monica.bitwarden.service

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import takagi.ru.monica.bitwarden.repository.BitwardenRepository

class BitwardenLoginErrorTest {
    @Test
    fun certificateChainFailureSurvivesNetworkAndHandshakeWrappers() {
        val certificate = CertPathValidatorException("Trust anchor for certification path not found")
        val handshake = SSLHandshakeException("Connection failed").apply { initCause(certificate) }
        val error = IOException("Network connection failed, retry timed out", handshake)

        assertEquals(BitwardenLoginError.CertificateUntrusted, classifyBitwardenLoginError(error))
    }

    @Test
    fun certificateFailuresWithoutMessagesKeepTheirMeaning() {
        assertEquals(BitwardenLoginError.CertificateUntrusted, classifyBitwardenLoginError(CertificateException()))
        assertEquals(BitwardenLoginError.CertificateExpired, classifyBitwardenLoginError(CertificateExpiredException()))
        assertEquals(BitwardenLoginError.CertificateNotYetValid, classifyBitwardenLoginError(CertificateNotYetValidException()))
    }

    @Test
    fun wrappedExpiredCertificateTakesPriorityOverGenericTrustFailure() {
        val certificate = CertPathValidatorException("Certificate chain validation failed", CertificateExpiredException())
        val error = SSLHandshakeException("Connection failed").apply { initCause(certificate) }
        assertEquals(BitwardenLoginError.CertificateExpired, classifyBitwardenLoginError(error))
    }

    @Test
    fun validatorReasonCanDescribeDatesWithoutAnInnerException() {
        val expired = CertPathValidatorException(null, null, null, -1, CertPathValidatorException.BasicReason.EXPIRED)
        val notYet = CertPathValidatorException(null, null, null, -1, CertPathValidatorException.BasicReason.NOT_YET_VALID)
        assertEquals(BitwardenLoginError.CertificateExpired, classifyBitwardenLoginError(expired))
        assertEquals(BitwardenLoginError.CertificateNotYetValid, classifyBitwardenLoginError(notYet))
    }

    @Test
    fun hostnameMismatchExplainsTheAddressAndProxyCertificate() {
        val error = IOException("Failed to connect", SSLPeerUnverifiedException("Hostname vault.example not verified"))
        assertEquals(BitwardenLoginError.CertificateHostnameMismatch, classifyBitwardenLoginError(error))
    }

    @Test
    fun missingSubjectAlternativeNameIsAHostnameFailure() {
        val error = CertificateException("No subject alternative DNS name matching vault.example found")
        assertEquals(BitwardenLoginError.CertificateHostnameMismatch, classifyBitwardenLoginError(error))
    }

    @Test
    fun platformSslMessagesCanIdentifyCertificateVerification() {
        val messages = listOf(
            "SSL routines:ssl3_get_server_certificate:certificate verify failed",
            "OPENSSL_internal:CERTIFICATE_VERIFY_FAILED",
            "Trust anchor for certification path not found",
            "unable to find valid certification path to requested target",
        )
        for (message in messages) {
            assertEquals(message, BitwardenLoginError.CertificateUntrusted, classifyBitwardenLoginError(SSLHandshakeException(message)))
        }
    }

    @Test
    fun protocolFailureIsNotPresentedAsAnUntrustedCertificate() {
        val error = SSLHandshakeException("Connection closed: received fatal alert protocol_version")
        assertEquals(BitwardenLoginError.TlsHandshake, classifyBitwardenLoginError(error))
        assertEquals(BitwardenLoginError.TlsHandshake, classifyBitwardenLoginError(SSLException("SSL protocol error")))
    }

    @Test
    fun handshakeTimeoutIsPresentedAsATimeout() {
        val error = SSLHandshakeException("Connection timed out").apply { initCause(SocketTimeoutException()) }
        assertEquals(BitwardenLoginError.Timeout, classifyBitwardenLoginError(error))
    }

    @Test
    fun dnsFailureKeepsItsTypeWhenWrappedOrWhenTheHostContainsTimeout() {
        val error = IOException("Network request failed", UnknownHostException("Unable to resolve host timeout.example"))
        assertEquals(BitwardenLoginError.Dns, classifyBitwardenLoginError(error))
        assertEquals(BitwardenLoginError.Dns, classifyBitwardenLoginError(UnknownHostException()))
    }

    @Test
    fun bothSocketAndOverallCallTimeoutsHaveSpecificGuidance() {
        assertEquals(BitwardenLoginError.Timeout, classifyBitwardenLoginError(IOException("request failed", SocketTimeoutException())))
        assertEquals(BitwardenLoginError.Timeout, classifyBitwardenLoginError(InterruptedIOException("timeout")))
        assertEquals(BitwardenLoginError.Timeout, classifyBitwardenLoginError(IOException("Read timed out")))
    }

    @Test
    fun refusedOrUnroutableConnectionsAreDifferentFromDnsAndTls() {
        assertEquals(BitwardenLoginError.Connection, classifyBitwardenLoginError(ConnectException()))
        assertEquals(BitwardenLoginError.Connection, classifyBitwardenLoginError(NoRouteToHostException()))
        assertEquals(BitwardenLoginError.Connection, classifyBitwardenLoginError(ConnectException("Failed to connect to timeout.example:443")))
        assertEquals(BitwardenLoginError.Connection, classifyBitwardenLoginError(ConnectException("Failed to connect to timeout:443")))
    }

    @Test
    fun proxyAuthenticationFailureDoesNotBecomeGenericConnectionFailure() {
        for (message in listOf("Failed to authenticate with proxy", "Unexpected response code for CONNECT: 407")) {
            assertEquals(BitwardenLoginError.Proxy, classifyBitwardenLoginError(IOException(message)))
        }
    }

    @Test
    fun blockedHttpAddressSuggestsHttps() {
        val error = UnknownServiceException("CLEARTEXT communication to vault.example not permitted by network security policy")
        assertEquals(BitwardenLoginError.CleartextBlocked, classifyBitwardenLoginError(error))
    }

    @Test
    fun remainingIoErrorsHaveAFallbackWithoutExposingRawExceptionText() {
        assertEquals(BitwardenLoginError.NetworkIo, classifyBitwardenLoginError(IOException("Connection reset")))
    }

    @Test
    fun serverResponseMentioningNetworkTermsStillUsesServerErrorHandling() {
        val error = Exception("PreLogin failed: 503 upstream connect timeout")
        assertNull(classifyBitwardenLoginError(error))
        assertEquals("服务器暂时不可用，请稍后重试", BitwardenRepository.parseErrorMessage(error.message))
    }

    @Test
    fun accountAndVerificationMessagesDoNotBecomeConnectionFailures() {
        val invalidPassword = Exception("Username or password is incorrect")
        val invalidCode = Exception("Two-step token is invalid")
        assertNull(classifyBitwardenLoginError(invalidPassword))
        assertNull(classifyBitwardenLoginError(invalidCode))
        assertEquals("两步验证码错误，请检查后重试", BitwardenRepository.parseErrorMessage(invalidCode.message))
    }

    @Test(timeout = 1_000)
    fun circularCauseChainsDoNotHangErrorReporting() {
        val first = IOException("request failed")
        val second = IllegalStateException("wrapper", first)
        first.initCause(second)
        assertEquals(BitwardenLoginError.NetworkIo, classifyBitwardenLoginError(first))
    }
}
