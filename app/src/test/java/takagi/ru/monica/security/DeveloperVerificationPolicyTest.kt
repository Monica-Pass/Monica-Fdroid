package takagi.ru.monica.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.AppSettings

class DeveloperVerificationPolicyTest {
    @Test
    fun `identity verification is required by default`() {
        val settings = AppSettings(disablePasswordVerification = false)

        assertTrue(DeveloperVerificationPolicy.requiresIdentityVerification(settings))
        assertFalse(DeveloperVerificationPolicy.bypassesIdentityVerification(settings))
    }

    @Test
    fun `developer setting bypasses identity verification globally`() {
        val settings = AppSettings(disablePasswordVerification = true)

        assertFalse(DeveloperVerificationPolicy.requiresIdentityVerification(settings))
        assertTrue(DeveloperVerificationPolicy.bypassesIdentityVerification(settings))
    }
}
