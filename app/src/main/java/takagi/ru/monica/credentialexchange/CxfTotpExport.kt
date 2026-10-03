package takagi.ru.monica.credentialexchange

import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.util.TotpDataResolver

/** CXF 1.0 §3.3.16. Only representable TOTP parameters may leave the vault. */
class CxfTotpExport private constructor(
    val secret: String, val period: Int, val digits: Int, val algorithm: String,
    val username: String, val issuer: String,
) {
    override fun toString() = "CxfTotpExport(<redacted>)"

    companion object {
        fun fromPayload(payload: String, issuer: String, username: String): CxfTotpExport? {
            if (payload.isBlank()) return null
            // Resolver is intentionally forgiving for display. Export must not repair invalid
            // parameters into a different code sequence.
            if (payload.contains("://")) {
                val parameters = runCatching {
                    val uri = java.net.URI(payload.trim())
                    uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
                        java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase() to
                            java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                    }
                }.getOrNull() ?: return null
                if (parameters["period"]?.let { it.toIntOrNull() !in 1..65535 } == true ||
                    parameters["digits"]?.let { it.toIntOrNull() !in 4..10 } == true) return null
            }
            val otp = TotpDataResolver.fromAuthenticatorKey(payload, issuer, username) ?: return null
            // Never relabel HOTP/Steam/Yandex/MOTP as a standard time-based code.
            if (otp.otpType != OtpType.TOTP || otp.period !in 1..65535 || otp.digits !in 4..10 ||
                otp.algorithm !in setOf("SHA1", "SHA256", "SHA512")) return null
            val secret = otp.secret.trimEnd('=')
            if (secret.isEmpty() || secret.length > 8192 || !secret.matches(Regex("[A-Z2-7]+")) ||
                secret.length % 8 !in setOf(0, 2, 4, 5, 7)) return null
            return CxfTotpExport(secret, otp.period, otp.digits, otp.algorithm.lowercase(),
                otp.accountName, otp.issuer)
        }
    }
}
