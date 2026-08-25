package takagi.ru.monica.utils

import android.content.Context
import android.util.Log
import takagi.ru.monica.security.SecurityManager

/**
 * Keeps a short-lived encrypted credential fallback around the non-transactional
 * boundary between rewriting a KDBX file and updating Monica's registration row.
 *
 * If the process stops after the file rewrite but before the Room update, the
 * next open can still try the new credentials and finish through the normal
 * re-verification flow.  Current registered credentials are always attempted
 * first, so a transition prepared before a failed write does not lock the user
 * out of the unchanged database.
 */
internal class KeePassCredentialTransitionStore(
    context: Context,
    private val securityManager: SecurityManager = SecurityManager(context.applicationContext),
    private val keyFileStore: KeePassKeyFileStore = KeePassKeyFileStore(context.applicationContext),
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    data class PendingCredentials(
        val password: String,
        val keyFileBytes: ByteArray?
    )

    fun prepare(
        databaseId: Long,
        password: String,
        keyFileBytes: ByteArray?,
        keyFileName: String?
    ): KeePassKeyFileStore.StoredKeyFile? {
        require(databaseId > 0L) { "Credential transition requires a database id" }
        val storedKeyFile = keyFileBytes?.let { bytes ->
            keyFileStore.copyBytes(bytes, keyFileName)
        }
        preferences.edit()
            .putString(passwordKey(databaseId), securityManager.encryptData(password))
            .putString(keyFileKey(databaseId), storedKeyFile?.relativePath)
            .putLong(createdAtKey(databaseId), nowProvider())
            .apply()
        return storedKeyFile
    }

    fun read(databaseId: Long): PendingCredentials? {
        val encryptedPassword = preferences.getString(passwordKey(databaseId), null) ?: return null
        val createdAt = preferences.getLong(createdAtKey(databaseId), 0L)
        if (createdAt <= 0L || nowProvider() - createdAt > MAX_AGE_MILLIS) {
            clear(databaseId)
            return null
        }
        return runCatching {
            val keyPath = preferences.getString(keyFileKey(databaseId), null)
            PendingCredentials(
                password = securityManager.decryptData(encryptedPassword),
                keyFileBytes = keyPath?.let(keyFileStore::readInternal)
            )
        }.onFailure { error ->
            Log.w(TAG, "Discarding unreadable KeePass credential transition", error)
            clear(databaseId)
        }.getOrNull()
    }

    fun clear(databaseId: Long) {
        preferences.edit()
            .remove(passwordKey(databaseId))
            .remove(keyFileKey(databaseId))
            .remove(createdAtKey(databaseId))
            .apply()
    }

    private fun passwordKey(databaseId: Long) = "database_${databaseId}_password"

    private fun keyFileKey(databaseId: Long) = "database_${databaseId}_key_file"

    private fun createdAtKey(databaseId: Long) = "database_${databaseId}_created_at"

    private companion object {
        const val TAG = "KeePassCredentialTxn"
        const val PREFERENCES_NAME = "keepass_credential_transitions"
        const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}
