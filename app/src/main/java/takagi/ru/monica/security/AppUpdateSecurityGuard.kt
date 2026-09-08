package takagi.ru.monica.security

import android.content.Context
import android.util.Log
import takagi.ru.monica.BuildConfig

/**
 * Forces Monica back to a locked state when the installed app version changes.
 *
 * Some OEMs may keep parts of the previous process alive across in-place updates,
 * which can incorrectly preserve in-memory unlock/session state. We explicitly
 * invalidate that state on every detected upgrade/downgrade.
 */
object AppUpdateSecurityGuard {

    private const val TAG = "AppUpdateSecurityGuard"
    private const val PREFS_NAME = "monica_app_update_guard"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
    private const val KEY_LAST_VERSION_NAME = "last_version_name"

    fun enforceLockIfAppUpdated(context: Context, reason: String) {
        val appContext = context.applicationContext

        // BuildConfig is generated from the installed APK's manifest version.
        // Reading it avoids a PackageManager query on every cold start while
        // preserving the same upgrade/downgrade detection semantics.
        val currentVersionCode = BuildConfig.VERSION_CODE.toLong()
        val currentVersionName = BuildConfig.VERSION_NAME
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersionCode = prefs.getLong(KEY_LAST_VERSION_CODE, Long.MIN_VALUE)
        val lastVersionName = prefs.getString(KEY_LAST_VERSION_NAME, null)

        val isFirstObservation = lastVersionCode == Long.MIN_VALUE && lastVersionName == null
        val versionChanged = !isFirstObservation &&
            (lastVersionCode != currentVersionCode || lastVersionName != currentVersionName)

        if (versionChanged) {
            Log.w(
                TAG,
                "App version changed ($lastVersionCode/$lastVersionName -> $currentVersionCode/$currentVersionName), forcing lock. reason=$reason"
            )
            SessionManager.markLocked()
        }

        if (isFirstObservation || versionChanged) {
            prefs.edit()
                .putLong(KEY_LAST_VERSION_CODE, currentVersionCode)
                .putString(KEY_LAST_VERSION_NAME, currentVersionName)
                .apply()
        }
    }
}
