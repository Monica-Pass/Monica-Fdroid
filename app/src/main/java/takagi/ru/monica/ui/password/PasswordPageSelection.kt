package takagi.ru.monica.ui.password

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.passwordProjectKey

internal fun expandPasswordProjectSelection(keys: Set<String>, entries: List<PasswordEntry>): Set<String> {
    val ids = selectedPasswordIds(keys)
    val projects = entries.filter { it.id in ids }.mapTo(hashSetOf()) { it.passwordProjectKey() }
    return keys + entries.filter { it.passwordProjectKey() in projects }.map { passwordSelectionKey(it.id) }
}

internal fun selectedPasswordProjectCount(keys: Set<String>, entries: List<PasswordEntry>): Int {
    val byId = entries.associateBy { it.id }
    return keys.map { key -> passwordIdFromSelectionKey(key)?.let { byId[it]?.passwordProjectKey() } ?: key }.distinct().size
}

private const val PASSWORD_SELECTION_PREFIX = "password:"

internal fun passwordSelectionKey(id: Long): String = "$PASSWORD_SELECTION_PREFIX$id"

internal fun selectionKeysForPasswords(ids: Iterable<Long>): Set<String> {
    return ids.mapTo(linkedSetOf(), ::passwordSelectionKey)
}

internal fun selectedPasswordIds(keys: Set<String>): Set<Long> {
    return keys.mapNotNullTo(linkedSetOf(), ::passwordIdFromSelectionKey)
}

internal fun passwordIdFromSelectionKey(key: String): Long? {
    return key.removePrefix(PASSWORD_SELECTION_PREFIX)
        .takeIf { it.length != key.length }
        ?.toLongOrNull()
}
