package takagi.ru.monica.viewmodel

/**
 * Finds display-only ghost rows while keeping secret resolution off the common list path.
 *
 * A secret is resolved only for rows that share a ghost-group key with at least one
 * other row. This preserves the old ghost semantics without decrypting every password
 * in the vault just to render the list.
 */
internal fun <T> findGhostDisplayIds(
    entries: List<T>,
    idOf: (T) -> Long,
    groupKeyOf: (T) -> String,
    isPasswordMode: (T) -> Boolean,
    shouldFilterGhost: (T) -> Boolean,
    resolveSecret: (T) -> String,
): Set<Long> {
    if (entries.size <= 1) return emptySet()

    val ghostIds = mutableSetOf<Long>()
    entries.groupBy(groupKeyOf).values.forEach { group ->
        if (group.size <= 1) return@forEach

        // This is intentionally the only place where secrets are resolved. A normal
        // list containing N unrelated rows therefore performs zero secret reads.
        val resolvedSecrets = group.associate { entry ->
            idOf(entry) to resolveSecret(entry)
        }
        if (resolvedSecrets.values.none(String::isNotBlank)) return@forEach

        group.forEach { entry ->
            if (
                isPasswordMode(entry) &&
                resolvedSecrets[idOf(entry)].isNullOrBlank() &&
                shouldFilterGhost(entry)
            ) {
                ghostIds += idOf(entry)
            }
        }
    }

    return ghostIds
}
