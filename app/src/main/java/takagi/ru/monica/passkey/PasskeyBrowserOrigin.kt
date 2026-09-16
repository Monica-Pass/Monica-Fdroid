package takagi.ru.monica.passkey

import android.content.Context
import androidx.credentials.provider.CallingAppInfo

/** AndroidX 1.6 requires an explicit browser signature allowlist for delegated origins. */
object PasskeyBrowserOrigin {
    @Volatile private var allowlist: String? = null

    fun read(context: Context, caller: CallingAppInfo?): String? {
        if (caller == null || !caller.isOriginPopulated()) return null
        val trusted = allowlist ?: synchronized(this) {
            allowlist ?: context.assets.open("passkey_privileged_apps.json").bufferedReader().use { it.readText() }
                .also { allowlist = it }
        }
        return runCatching { caller.getOrigin(trusted) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
