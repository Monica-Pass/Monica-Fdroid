package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.regenerateVectors
import java.security.SecureRandom
import java.time.Instant
import takagi.ru.monica.utils.KeePassCodecSupport

internal object KeePassDatabaseCredentialEditor {
    fun replace(
        database: KeePassDatabase,
        credentials: Credentials,
        nowProvider: () -> Instant = Instant::now,
        random: SecureRandom = SecureRandom(),
        cipherProviders: List<CipherProvider> = KeePassCodecSupport.cipherProviders
    ): KeePassDatabase {
        val now = nowProvider()
        val updatedMeta = database.content.meta.copy(masterKeyChanged = now)
        val updated = when (database) {
            is KeePassDatabase.Ver3x -> database.copy(
                credentials = credentials,
                content = database.content.copy(meta = updatedMeta)
            )
            is KeePassDatabase.Ver4x -> database.copy(
                credentials = credentials,
                content = database.content.copy(meta = updatedMeta)
            )
        }
        return updated.regenerateVectors(random = random, cipherProviders = cipherProviders)
    }
}

internal enum class KeePassKeyFileChangeMode {
    KEEP_CURRENT,
    REMOVE,
    REPLACE
}

internal data class KeePassMasterCredentialChangeResult(
    val settings: KeePassDatabaseSettingsSnapshot,
    val retainedInternalKeyFile: Boolean
)
