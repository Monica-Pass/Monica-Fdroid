package takagi.ru.monica.utils

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import takagi.ru.monica.data.Language

private const val STARTUP_LANGUAGE_PREFS = "monica_startup_language"
private const val STARTUP_LANGUAGE_KEY = "language"
private const val LEGACY_DATASTORE_MIGRATION_TIMEOUT_MS = 200L

internal fun parseStartupLanguage(value: String?): Language =
    value?.let { raw ->
        runCatching { Language.valueOf(raw) }.getOrNull()
    } ?: Language.SYSTEM

/**
 * Small startup-only mirror of the DataStore language setting.
 *
 * Activity.attachBaseContext() needs the language before normal coroutine
 * collection can start. A SharedPreferences mirror avoids waiting for the
 * complete AppSettings DataStore on every Activity creation. Existing installs
 * perform one bounded migration when the mirror is first introduced.
 */
object StartupLanguageCache {

    @Volatile
    private var processLanguage: Language? = null

    fun read(context: Context): Language {
        processLanguage?.let { return it }

        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            STARTUP_LANGUAGE_PREFS,
            Context.MODE_PRIVATE,
        )
        val cachedValue = if (preferences.contains(STARTUP_LANGUAGE_KEY)) {
            parseStartupLanguage(preferences.getString(STARTUP_LANGUAGE_KEY, null))
        } else {
            migrateLegacyDataStoreLanguage(appContext)
        }
        processLanguage = cachedValue
        return cachedValue
    }

    fun write(context: Context, language: Language) {
        val appContext = context.applicationContext
        processLanguage = language
        val preferences = appContext.getSharedPreferences(
            STARTUP_LANGUAGE_PREFS,
            Context.MODE_PRIVATE,
        )
        if (preferences.getString(STARTUP_LANGUAGE_KEY, null) == language.name) return
        preferences.edit().putString(STARTUP_LANGUAGE_KEY, language.name).apply()
    }

    private fun migrateLegacyDataStoreLanguage(context: Context): Language {
        val dataStoreFile = File(context.filesDir, "datastore/settings.preferences_pb")
        val migratedLanguage = if (dataStoreFile.isFile) {
            runBlocking {
                withTimeoutOrNull(LEGACY_DATASTORE_MIGRATION_TIMEOUT_MS) {
                    runCatching {
                        SettingsManager(context).settingsFlow.first().language
                    }.getOrDefault(Language.SYSTEM)
                }
            } ?: Language.SYSTEM
        } else {
            Language.SYSTEM
        }
        write(context, migratedLanguage)
        return migratedLanguage
    }
}
