package takagi.ru.monica.ui.screens

import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.util.OtpParametersDraft
import takagi.ru.monica.util.TotpDataResolver

internal data class PasswordScreenAuthenticatorDraft(
    val secret: String,
    val otpType: OtpType,
    val parameters: OtpParametersDraft = OtpParametersDraft(),
)

internal fun resolvePasswordScreenAuthenticatorDraft(
    rawKey: String,
    fallbackIssuer: String = "",
    fallbackAccountName: String = "",
): PasswordScreenAuthenticatorDraft {
    val resolved = TotpDataResolver.fromAuthenticatorKey(rawKey, fallbackIssuer, fallbackAccountName)
    return if (resolved != null) {
        PasswordScreenAuthenticatorDraft(resolved.secret, resolved.otpType, OtpParametersDraft.from(resolved))
    } else {
        PasswordScreenAuthenticatorDraft(rawKey.trim(), OtpType.TOTP)
    }
}

internal fun buildPasswordScreenAuthenticatorPayload(
    secret: String,
    otpType: OtpType,
    issuer: String,
    accountName: String,
    parameters: OtpParametersDraft = OtpParametersDraft(),
): String {
    if (secret.isBlank()) return ""
    return TotpDataResolver.toBitwardenPayload(
        title = issuer,
        data = parameters.toData(secret, otpType, issuer, accountName),
    )
}
