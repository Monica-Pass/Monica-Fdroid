package takagi.ru.monica.viewmodel

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.toStorageTarget

/**
 * Returns an identity that proves two password rows are replicas of the same
 * source object. A missing identity is intentional: a normal local row is an
 * independent user-created item even when every visible field matches another
 * row, so it must not be hidden by the All-view display deduplicator.
 */
internal fun passwordDisplayStableIdentityKey(entry: PasswordEntry): String? {
    entry.replicaGroupId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return "replica:$it" }

    if (entry.bitwardenVaultId != null && !entry.bitwardenCipherId.isNullOrBlank()) {
        return "bitwarden:${entry.bitwardenVaultId}:${entry.bitwardenCipherId}"
    }
    if (entry.keepassDatabaseId != null && !entry.keepassEntryUuid.isNullOrBlank()) {
        return "keepass:${entry.keepassDatabaseId}:${entry.keepassEntryUuid}"
    }

    // MDBX rows normally carry replicaGroupId (password:<object id>). When a
    // legacy row does not, returning null preserves it as an independent row.
    return null
}

/**
 * Collapses only rows with the same explicit source identity. Rows without an
 * identity are independent user rows and are returned one-for-one.
 */
internal fun dedupePasswordDisplayRows(
    rows: List<Pair<PasswordEntry, String?>>,
    pickBest: (List<PasswordEntry>) -> PasswordEntry?
): List<PasswordEntry> {
    if (rows.size <= 1) return rows.map { it.first }

    return rows
        .groupBy { (entry, _) -> passwordDisplayStableIdentityKey(entry) }
        .flatMap { (identity, identityRows) ->
            if (identity == null) {
                identityRows.map { it.first }
            } else if (identity.startsWith("replica:")) {
                // A single replica group can intentionally contain multiple
                // passwords in the same target. Keep those sibling rows; only
                // collapse the one-row target replicas against each other.
                val targetGroups = identityRows.groupBy { (entry, _) ->
                    entry.toStorageTarget().stableKey
                }
                val siblingRows = targetGroups
                    .filterValues { it.size > 1 }
                    .values
                    .flatten()
                    .map { it.first }
                val singletonRows = targetGroups
                    .filterValues { it.size == 1 }
                    .values
                    .flatten()

                val singletonBuckets = singletonRows
                    .filter { row -> row.second != null }
                    .groupBy({ it.second!! }, { it.first })
                val collapsedSingletons = if (singletonBuckets.isEmpty()) {
                    pickBest(singletonRows.map { it.first })?.let(::listOf).orEmpty()
                } else {
                    singletonBuckets.values.mapNotNull(pickBest)
                }
                siblingRows + collapsedSingletons
            } else {
                val knownPasswordBuckets = identityRows
                    .filter { (_, password) -> password != null }
                    .groupBy({ (_, password) -> password!! }, { (entry, _) -> entry })
                if (knownPasswordBuckets.isEmpty()) {
                    pickBest(identityRows.map { it.first })?.let(::listOf).orEmpty()
                } else {
                    knownPasswordBuckets.values.mapNotNull(pickBest)
                }
            }
        }
}
