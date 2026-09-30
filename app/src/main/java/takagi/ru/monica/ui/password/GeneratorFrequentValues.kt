package takagi.ru.monica.ui.password

import takagi.ru.monica.data.PasswordEntry

/** Values are exact: changing case or whitespace can change a credential. */
internal fun generatorFrequentValues(values: List<String>, presets: Set<String>, limit: Int = 5): List<String> =
    values.asSequence().filter { it.isNotBlank() && it !in presets }
        .groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit.coerceAtLeast(0)).map { it.key }

internal fun generatorEntryAccessible(entry: PasswordEntry, sources: Set<String>): Boolean {
    if (entry.isDeleted || entry.isArchived) return false
    if (listOf(entry.keepassDatabaseId, entry.bitwardenVaultId, entry.mdbxDatabaseId).count { it != null } > 1) return false
    val source = when {
        entry.keepassDatabaseId != null -> "keepass:${entry.keepassDatabaseId}"
        entry.bitwardenVaultId != null -> "bitwarden:${entry.bitwardenVaultId}"
        entry.mdbxDatabaseId != null -> "mdbx:${entry.mdbxDatabaseId}"
        else -> "local"
    }
    return source in sources
}
