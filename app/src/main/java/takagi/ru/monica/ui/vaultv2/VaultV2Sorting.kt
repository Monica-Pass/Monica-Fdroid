package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.data.VaultListSort
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun VaultV2Item.sortTimestamp(sort: VaultListSort): Long? {
    val created = when {
        nativeToken != null -> null // The native summary does not expose creation time.
        passwordEntry != null -> passwordEntry.createdAt.time
        totpItem != null -> totpItem.createdAt.time
        secureItem != null -> secureItem.createdAt.time
        else -> passkeyEntry?.createdAt
    }
    val updated = nativeToken?.updatedAt ?: passwordEntry?.updatedAt?.time ?:
        totpItem?.updatedAt?.time ?: secureItem?.updatedAt?.time
    if (sort == VaultListSort.RECENT_DESC) {
        return listOfNotNull(created, updated).filter { it > 0L }.maxOrNull()
    }
    // Passkeys have no modification timestamp; lastUsedAt is usage, not an edit.
    return (if (sort == VaultListSort.CREATED_ASC || sort == VaultListSort.CREATED_DESC) created
        else updated ?: created)?.takeIf { it > 0L }
}

internal fun sortVaultV2Items(items: List<VaultV2Item>, sort: VaultListSort): List<VaultV2Item> {
    val titleComparator = compareBy<VaultV2Item> { firstLetterGroup(it.sortKey) == "#" }
        .thenComparator { a, b ->
            val result = a.sortKey.lowercase(Locale.ROOT).compareTo(b.sortKey.lowercase(Locale.ROOT))
            if (sort == VaultListSort.TITLE_DESC) -result else result
        }
        .thenBy { it.type.ordinal }.thenBy { it.key }
    if (sort.isAlphabetical) return items.sortedWith(titleComparator)
    return items.sortedWith(compareBy<VaultV2Item> { it.sortTimestamp(sort) == null }
        .thenComparator { a, b ->
            val result = (a.sortTimestamp(sort) ?: 0).compareTo(b.sortTimestamp(sort) ?: 0)
            if (sort.descending) -result else result
        }.then(titleComparator))
}

/** Preserve the chosen order when constructing headers and fast-scroll offsets. */
internal fun buildVaultV2SortedSections(
    items: List<VaultV2Item>,
    sort: VaultListSort,
): List<Pair<String, List<VaultV2Item>>> {
    if (sort.isAlphabetical) {
        val groups = items.groupBy { firstLetterGroup(it.sortKey) }
        return groups.keys.sortedWith(compareBy<String> { it == "#" }.thenComparator { a, b ->
            if (sort.descending) b.compareTo(a) else a.compareTo(b)
        }).map { it to groups.getValue(it) }
    }
    val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.ROOT)
    return items.groupBy { item ->
        item.sortTimestamp(sort)?.let { dateFormat.format(Date(it)) }.orEmpty()
    }.toList()
}
