package takagi.ru.monica.passkey

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PasskeyUserVerificationGuardTest {
    private fun source(name: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (root.parentFile != null && !File(root, "settings.gradle.kts").exists() && !File(root, "settings.gradle").exists()) root = root.parentFile!!
        return File(root, "app/src/main/java/takagi/ru/monica/passkey/$name.kt").readText()
    }
    @Test fun authenticationAndCreationRequireVerificationAndRetainPasswordFallback() {
        for (name in listOf("PasskeyAuthActivity", "PasskeyCreateActivity")) {
            val text = source(name)
            val biometric = text.substringAfter("private fun requestBiometricAuth(").substringBefore("private fun ")
            assertTrue(biometric.contains("biometricHelper.authenticate("))
            assertTrue(biometric.contains("!biometricHelper.isBiometricAvailable()"))
            assertTrue(biometric.contains("showMasterPasswordDialog.value = true"))
            assertTrue(text.contains("if (securityManager.verifyMasterPassword(password))"))
            assertTrue(text.contains("MasterPasswordDialog("))
            assertFalse(text.contains("shouldBypassBiometric"))
            assertFalse(text.contains("BIOMETRIC_BYPASSED"))
        }
        assertTrue(source("PasskeyAuthActivity").contains("requestBiometricAuth(currentPasskey)"))
        val create = source("PasskeyCreateActivity")
        assertEquals(2, Regex("(?m)^ +requestBiometricAuth\\(\\)$").findAll(create).count())
        val error = create.substringAfter("onError = biometricError@").substringBefore("onCancel =")
        assertTrue(error.contains("securityManager.isMasterPasswordSet()"))
        assertTrue(error.contains("showMasterPasswordDialog.value = true"))
        assertTrue(error.contains("return@biometricError"))
    }
}
