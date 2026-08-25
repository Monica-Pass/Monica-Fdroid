package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2SecurityWiringGuardTest {

    @Test
    fun `compact vault passes security dependencies to the vault pane`() {
        val compactContent = source("ui/CompactDraggableTabContent.kt")
        val vaultCall = compactContent
            .substringAfter("BottomNavItem.VaultV2 ->")
            .substringAfter("VaultV2Pane(")
            .substringBefore("BottomNavItem.Passwords ->")

        assertTrue(vaultCall.contains("securityManager = securityManager"))
        assertTrue(vaultCall.contains("biometricEnabled = appSettings.biometricEnabled"))
    }

    @Test
    fun `vault pane cannot silently omit the security manager`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val signature = pane
            .substringAfter("fun VaultV2Pane(")
            .substringBefore(") {")
        val itemCard = pane.substringAfter("private fun VaultV2ItemCard(")

        assertTrue(signature.contains("securityManager: SecurityManager,"))
        assertTrue(signature.contains("biometricEnabled: Boolean,"))
        assertTrue(
            itemCard.substringBefore(") {").contains("securityManager: SecurityManager,")
        )
        assertTrue(itemCard.contains("decryptAuthenticatorKey = { value: String ->"))
        assertTrue(itemCard.contains("securityManager.decryptDataIfMonicaCiphertext(value)"))
        assertTrue(pane.contains("if (securityManager.unlockVaultWithPassword(password))"))
        assertFalse(pane.contains("if (sm != null && sm.unlockVaultWithPassword(password))"))
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/takagi/ru/monica/$relativePath"
        ).readText()
    }
}
