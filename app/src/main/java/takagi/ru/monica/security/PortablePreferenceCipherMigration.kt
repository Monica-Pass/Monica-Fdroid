package takagi.ru.monica.security

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** The participating stores use [lock] for writes, so maintenance cannot resurrect a cleared value. */
internal object PortablePreferenceCipherMigration {
    val lock = Any()

    suspend fun run(context: Context, security: SecurityManager): Int {
        var changed = 0
        val stores = mapOf(
            "bitwarden_offline_secret_cache" to Regex("secret_[0-9]+"),
            "keepass_credential_transitions" to Regex("database_[0-9]+_password")
        )
        for ((store, keys) in stores) {
            val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)
            val names = synchronized(lock) { prefs.all.keys.filter(keys::matches) }
            for (name in names) {
                currentCoroutineContext().ensureActive()
                if (!security.isVaultRuntimeUnlocked()) return changed
                synchronized(lock) {
                    val old = prefs.getString(name, null) ?: return@synchronized
                    val replacement = runCatching {
                        PortableLocalCipherMigration.portableCipher(old, security)
                    }.getOrNull() ?: return@synchronized
                    if (security.isVaultRuntimeUnlocked() && prefs.getString(name, null) == old) {
                        check(prefs.edit().putString(name, replacement).commit())
                        changed++
                    }
                }
                yield()
            }
        }
        return changed
    }
}
