package takagi.ru.monica.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import java.util.Locale

/** Uses the locale selected by LocaleHelper without retaining an Activity. */
internal class AppLocaleStringResolver(context: Context) : StringResolver {
    private val applicationContext = context.applicationContext

    private data class LocalizedResources(val locale: Locale, val resources: Resources)

    @Volatile
    private var cachedResources: LocalizedResources? = null

    override fun get(@StringRes resourceId: Int, vararg arguments: Any): String {
        val locale = Locale.getDefault()
        val resources = cachedResources?.takeIf { it.locale == locale }?.resources
            ?: applicationContext.createConfigurationContext(
                Configuration(applicationContext.resources.configuration).apply { setLocale(locale) }
            ).resources.also { cachedResources = LocalizedResources(locale, it) }
        return if (arguments.isEmpty()) resources.getString(resourceId)
        else resources.getString(resourceId, *arguments)
    }
}
