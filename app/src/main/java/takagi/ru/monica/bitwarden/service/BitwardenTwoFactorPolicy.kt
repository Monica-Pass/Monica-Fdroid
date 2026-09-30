package takagi.ru.monica.bitwarden.service

import takagi.ru.monica.bitwarden.api.TokenResponse

/** Recognize both Identity response formats without treating an MFA challenge as a login. */
internal object BitwardenTwoFactorPolicy {
    fun providers(response: TokenResponse): List<Int> =
        (response.twoFactorProviders.orEmpty() + response.twoFactorProviders2.orEmpty().keys)
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 && it != BitwardenAuthService.TWO_FACTOR_REMEMBER }
            .distinct()

    fun supportsCode(provider: Int): Boolean = provider in setOf(
        BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR,
        BitwardenAuthService.TWO_FACTOR_EMAIL,
        BitwardenAuthService.TWO_FACTOR_YUBIKEY,
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE,
    )

    fun preferred(providers: List<Int>): Int = listOf(
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE,
        BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR,
        BitwardenAuthService.TWO_FACTOR_EMAIL,
        BitwardenAuthService.TWO_FACTOR_YUBIKEY,
    ).firstOrNull { it in providers } ?: providers.firstOrNull() ?: -1

    fun normalizeCode(provider: Int, code: String): String = when (provider) {
        BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR,
        BitwardenAuthService.TWO_FACTOR_EMAIL,
        BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE -> code.filterNot { it.isWhitespace() }
        else -> code.trim()
    }
}
