package takagi.ru.monica.bitwarden.service

import android.content.Context

/**
 * Records whether a cipher has completed one adapter-aware custom-field synchronization.
 * This prevents the first update after an upgrade from treating previously unseen remote fields
 * as deliberate local deletions.
 */
internal class BitwardenPasswordCustomFieldSyncState(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun isInitialized(vaultId: Long, cipherId: String): Boolean {
        return preferences.getBoolean(key(vaultId, cipherId), false)
    }

    fun markInitialized(vaultId: Long, cipherId: String) {
        preferences.edit().putBoolean(key(vaultId, cipherId), true).apply()
    }

    private fun key(vaultId: Long, cipherId: String): String = "$vaultId:$cipherId"

    private companion object {
        const val PREFERENCES_NAME = "bitwarden_password_custom_field_sync_state"
    }
}
