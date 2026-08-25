package takagi.ru.monica.utils

internal object BackupEncryptionPolicy {
    fun resolvePassword(
        allowBackupEncryption: Boolean,
        explicitPassword: String?,
        configuredEncryptionEnabled: Boolean,
        configuredEncryptionPassword: String,
    ): String? {
        if (!allowBackupEncryption) return null

        explicitPassword?.takeIf { it.isNotBlank() }?.let { return it }
        return configuredEncryptionPassword.takeIf {
            configuredEncryptionEnabled && it.isNotEmpty()
        }
    }
}
