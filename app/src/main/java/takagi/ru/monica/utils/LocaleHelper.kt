package takagi.ru.monica.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
import takagi.ru.monica.data.Language

object LocaleHelper {

    fun setLocale(context: Context, language: Language): Context {
        val locale = when (language) {
            Language.SYSTEM -> getSystemLocale()
            Language.ENGLISH -> Locale.ENGLISH
            Language.CHINESE -> Locale.CHINA
            Language.VIETNAMESE -> Locale("vi", "VN")
            Language.JAPANESE -> Locale.JAPAN
            Language.RUSSIAN -> Locale("ru", "RU")
            Language.KOREAN -> Locale.KOREA
            Language.GERMAN -> Locale.GERMANY
            Language.SPANISH -> Locale("es", "ES")
        }

        return updateResources(context, locale)
    }

    private fun getSystemLocale(): Locale {
        val systemConfig = android.content.res.Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            systemConfig.locales[0]
        } else {
            @Suppress("DEPRECATION")
            systemConfig.locale
        }
    }

    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        // Most cold starts already have the requested locale (especially
        // Language.SYSTEM). Avoid cloning Configuration and creating another
        // Context in that common path while still resetting Locale.default.
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        if (currentLocale.language == locale.language && currentLocale.country == locale.country) {
            return context
        }

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    fun getCurrentLanguage(context: Context): Language {
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }

        return when (currentLocale.language) {
            "zh" -> Language.CHINESE
            "en" -> Language.ENGLISH
            "vi" -> Language.VIETNAMESE
            "ja" -> Language.JAPANESE
            "ru" -> Language.RUSSIAN
            "ko" -> Language.KOREAN
            "de" -> Language.GERMAN
            "es" -> Language.SPANISH
            else -> Language.SYSTEM
        }
    }
}
