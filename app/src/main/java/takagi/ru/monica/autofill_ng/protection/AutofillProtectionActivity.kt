package takagi.ru.monica.autofill_ng.protection

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.ui.screens.AutofillProtectionScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

/** Permission/status UI is available without opening or extending a password vault session. */
class AutofillProtectionActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by remember { SettingsManager(applicationContext).settingsFlow }
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            MonicaTheme(
                darkTheme = when (settings.themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                },
                oledPureBlackEnabled = settings.oledPureBlackEnabled,
                colorScheme = settings.colorScheme,
                customPrimaryColor = settings.customPrimaryColor,
                customSecondaryColor = settings.customSecondaryColor,
                customTertiaryColor = settings.customTertiaryColor,
                customNeutralColor = settings.customNeutralColor,
                customNeutralVariantColor = settings.customNeutralVariantColor,
            ) {
                AutofillProtectionScreen(onNavigateBack = ::finish)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AutofillProtection.restoreIfEnabled(this)
        AutofillProtection.refresh()
    }
}
