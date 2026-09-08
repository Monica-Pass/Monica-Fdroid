package takagi.ru.monica.ui.base

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.ScreenshotProtectionUtil
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

/**
 * Monica 应用的统一基类 Activity。
 *
 * Startup note: settings are lazy. MainActivity owns the settings instance used
 * by Compose, so constructing another SettingsManager in BaseMonicaActivity
 * before setContent only duplicated DataStore setup/migration work. Secondary
 * activities still receive the same base behavior once they reach STARTED.
 */
abstract class BaseMonicaActivity : FragmentActivity() {

    protected val settingsManager: SettingsManager by lazy(LazyThreadSafetyMode.NONE) {
        SettingsManager(applicationContext)
    }

    protected var cachedSettings: AppSettings? = null

    override fun attachBaseContext(newBase: Context?) {
        if (newBase != null) {
            val language = StartupLanguageCache.read(newBase)
            super.attachBaseContext(LocaleHelper.setLocale(newBase, language))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeSystemBars()
        disableSystemAutofillForMonicaUi()

        // Do not instantiate SettingsManager here. repeatOnLifecycle reaches
        // this block only once the Activity is STARTED, outside the cold-start
        // onCreate/setContent critical path.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsManager.settingsFlow.collect { settings ->
                    cachedSettings = settings
                    StartupLanguageCache.write(applicationContext, settings.language)
                    applyScreenshotProtection(settings.screenshotProtectionEnabled)
                    SessionManager.updateAutoLockTimeout(settings.autoLockMinutes)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        disableSystemAutofillForMonicaUi()

        cachedSettings?.let { SessionManager.updateAutoLockTimeout(it.autoLockMinutes) }

        if (!shouldEnforceSharedSessionLock()) {
            return
        }

        if (SessionManager.isSessionExpired()) {
            SessionManager.markLocked()
            onSessionExpired()
        } else {
            SessionManager.refreshSession()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionManager.refreshSession()
    }

    override fun onStop() {
        SessionManager.flushPendingRefresh()
        super.onStop()
    }

    protected fun applyScreenshotProtection(enabled: Boolean) {
        if (enabled) {
            ScreenshotProtectionUtil.enableScreenshotProtection(this)
        } else {
            ScreenshotProtectionUtil.disableScreenshotProtection(this)
        }
    }

    protected open fun onSessionExpired() {
        android.util.Log.d("BaseMonicaActivity", "Session expired")
    }

    protected open fun shouldEnforceSharedSessionLock(): Boolean = true

    protected fun markAuthenticationSuccess() {
        SessionManager.markUnlocked()
    }

    private fun disableSystemAutofillForMonicaUi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        window?.decorView?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        findViewById<View?>(android.R.id.content)?.importantForAutofill =
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun configureEdgeToEdgeSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }
}
