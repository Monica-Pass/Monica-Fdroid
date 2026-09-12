package takagi.ru.monica.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.vaultOverviewUsageStore by preferencesDataStore("vault_overview_usage")

@Serializable
data class VaultOverviewUsage(val key: String, val count: Int, val lastOpenedAt: Long)

fun vaultOverviewSourceKey(bitwardenId: Long?, keepassId: Long?, mdbxId: Long?): String = when {
    bitwardenId != null -> "bitwarden:$bitwardenId"
    keepassId != null -> "keepass:$keepassId"
    mdbxId != null -> "mdbx:$mdbxId"
    else -> "local"
}

fun PasswordEntry.vaultOverviewKey(): String =
    "${vaultOverviewSourceKey(bitwardenVaultId, keepassDatabaseId, mdbxDatabaseId)}/password/" +
        (bitwardenCipherId?.takeIf(String::isNotBlank) ?: keepassEntryUuid?.takeIf(String::isNotBlank)
            ?: "$id:${createdAt.time}")

fun SecureItem.vaultOverviewKey(): String =
    "${vaultOverviewSourceKey(bitwardenVaultId, keepassDatabaseId, mdbxDatabaseId)}/${itemType.name}/" +
        (bitwardenCipherId?.takeIf(String::isNotBlank) ?: keepassEntryUuid?.takeIf(String::isNotBlank)
            ?: "$id:${createdAt.time}")

fun PasskeyEntry.vaultOverviewKey(): String =
    "${vaultOverviewSourceKey(bitwardenVaultId, keepassDatabaseId, mdbxDatabaseId)}/passkey/$credentialId"

/** Stores identities and usage counters, never titles, credentials or card contents. */
class VaultOverviewUsageManager(context: Context) {
    private val store = context.applicationContext.vaultOverviewUsageStore
    private val key = stringPreferencesKey("records")
    private val json = Json { ignoreUnknownKeys = true }

    val stats = store.data.map { decode(it[key]).associateBy(VaultOverviewUsage::key) }
        .flowOn(Dispatchers.Default)

    suspend fun recordOpen(itemKey: String) {
        if (itemKey.isBlank()) return
        store.edit { preferences ->
            val records = decode(preferences[key]).associateBy(VaultOverviewUsage::key).toMutableMap()
            val oldCount = records[itemKey]?.count ?: 0
            records[itemKey] = VaultOverviewUsage(itemKey,
                if (oldCount < Int.MAX_VALUE) oldCount + 1 else oldCount, System.currentTimeMillis())
            preferences[key] = json.encodeToString(records.values.sortedByDescending { it.lastOpenedAt }.take(2000))
        }
    }

    private fun decode(raw: String?): List<VaultOverviewUsage> = raw?.let {
        runCatching { json.decodeFromString<List<VaultOverviewUsage>>(it) }.getOrNull()
    }.orEmpty().filter { it.key.isNotBlank() && it.count > 0 }
}
