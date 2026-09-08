package takagi.ru.monica.security

import takagi.ru.monica.data.AppSettings

/** Single policy point for the developer-only full identity-verification bypass. */
object DeveloperVerificationPolicy {
    fun bypassesIdentityVerification(settings: AppSettings): Boolean =
        settings.disablePasswordVerification

    fun requiresIdentityVerification(settings: AppSettings): Boolean =
        !bypassesIdentityVerification(settings)
}
