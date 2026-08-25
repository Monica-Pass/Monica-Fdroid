package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupEncryptionPolicyTest {

    @Test
    fun localPlainExportDoesNotInheritPersistedWebDavPassword() {
        assertNull(
            BackupEncryptionPolicy.resolvePassword(
                allowBackupEncryption = false,
                explicitPassword = null,
                configuredEncryptionEnabled = true,
                configuredEncryptionPassword = "remote-password",
            )
        )
    }

    @Test
    fun explicitExportPasswordWinsOverPersistedWebDavPassword() {
        assertEquals(
            "local-password",
            BackupEncryptionPolicy.resolvePassword(
                allowBackupEncryption = true,
                explicitPassword = "local-password",
                configuredEncryptionEnabled = true,
                configuredEncryptionPassword = "remote-password",
            )
        )
    }

    @Test
    fun remoteBackupCanReusePersistedWebDavPassword() {
        assertEquals(
            "remote-password",
            BackupEncryptionPolicy.resolvePassword(
                allowBackupEncryption = true,
                explicitPassword = null,
                configuredEncryptionEnabled = true,
                configuredEncryptionPassword = "remote-password",
            )
        )
    }

    @Test
    fun disabledPersistedEncryptionDoesNotCreateEncryptedBackup() {
        assertNull(
            BackupEncryptionPolicy.resolvePassword(
                allowBackupEncryption = true,
                explicitPassword = null,
                configuredEncryptionEnabled = false,
                configuredEncryptionPassword = "",
            )
        )
    }
}
