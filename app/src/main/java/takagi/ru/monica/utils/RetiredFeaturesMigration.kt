package takagi.ru.monica.utils

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences

/** Removes retired preferences without resetting unrelated settings or touching vault data. */
internal object RetiredFeaturesMigration : DataMigration<Preferences> {
    private val retiredNames = setOf(
        "use_draggable_bottom_nav",
        "passkey_hyperos_biometric_bypass_enabled",
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().keys.any { it.name in retiredNames }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        asMap().keys.filter { it.name in retiredNames }.forEach { remove(it) }
    }

    override suspend fun cleanUp() = Unit
}
