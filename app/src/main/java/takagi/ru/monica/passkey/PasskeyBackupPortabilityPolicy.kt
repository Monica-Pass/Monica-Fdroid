package takagi.ru.monica.passkey

/**
 * Defines how passkey private-key material crosses a backup boundary.
 *
 * Room may contain only a protected local reference. A portable backup must
 * resolve that reference and serialize validated PKCS#8 material, and that is
 * permitted only when the whole backup is encrypted.
 */
internal object PasskeyBackupPortabilityPolicy {
    private const val SYNC_STATUS_LOCAL = "NONE"
    private const val SYNC_STATUS_REFERENCE = "REFERENCE"

    sealed interface ExportDecision {
        data class Ready(val privateKeyMaterial: String) : ExportDecision
        data object EncryptionRequired : ExportDecision
        data object PrivateKeyMissing : ExportDecision
    }

    data class RestoreDecision(
        val privateKeyMaterial: String,
        val syncStatus: String,
        val privateKeyMissing: Boolean,
    )

    fun prepareExport(
        encryptedBackup: Boolean,
        storedPrivateKey: String?,
        resolvePrivateKey: (String?) -> String?,
        normalizePrivateKey: (String?) -> String?,
    ): ExportDecision {
        if (!encryptedBackup) return ExportDecision.EncryptionRequired

        val normalized = normalizePrivateKey(resolvePrivateKey(storedPrivateKey))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ExportDecision.PrivateKeyMissing

        return ExportDecision.Ready(normalized)
    }

    fun prepareRestore(
        storedPrivateKey: String?,
        resolvePrivateKey: (String?) -> String?,
        normalizePrivateKey: (String?) -> String?,
    ): RestoreDecision {
        val normalized = normalizePrivateKey(resolvePrivateKey(storedPrivateKey))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return if (normalized == null) {
            RestoreDecision(
                privateKeyMaterial = "",
                syncStatus = SYNC_STATUS_REFERENCE,
                privateKeyMissing = true,
            )
        } else {
            RestoreDecision(
                privateKeyMaterial = normalized,
                syncStatus = SYNC_STATUS_LOCAL,
                privateKeyMissing = false,
            )
        }
    }
}
