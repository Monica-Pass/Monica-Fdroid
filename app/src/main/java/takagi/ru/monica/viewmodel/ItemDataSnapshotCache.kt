package takagi.ru.monica.viewmodel

import takagi.ru.monica.data.SecureItem

/** Holds only the current snapshot; changes to title/order reuse the decrypted payload. */
internal class ItemDataSnapshotCache<T> {
    private data class Entry<T>(val encoded: String, val parsed: T)
    private var entries = emptyMap<Long, Entry<T>>()

    @Synchronized
    fun parse(items: List<SecureItem>, parser: (String) -> T?): List<T?> {
        val next = HashMap<Long, Entry<T>>(items.size)
        val result = items.map { item ->
            val entry = entries[item.id]?.takeIf { it.encoded == item.itemData }
                ?: parser(item.itemData)?.let { Entry<T>(item.itemData, it) }
            // A temporarily unavailable decryption key must be retryable.
            if (entry != null) next[item.id] = entry
            entry?.parsed
        }
        entries = next
        return result
    }
}
