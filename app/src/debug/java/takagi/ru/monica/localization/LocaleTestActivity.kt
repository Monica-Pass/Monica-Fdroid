package takagi.ru.monica.localization

import android.content.Context
import androidx.activity.ComponentActivity
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache

/** Gives popup and dialog windows the same localized base context as the app. */
class LocaleTestActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }
}
