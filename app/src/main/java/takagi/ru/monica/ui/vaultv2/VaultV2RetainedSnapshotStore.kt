package takagi.ru.monica.ui.vaultv2

internal data class VaultV2SnapshotSeed<T>(
    val value: T,
    val hasSnapshot: Boolean
)

internal class VaultV2RetainedSnapshotStore<K, T>(
    private val maxEntries: Int = 4
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val snapshots = LinkedHashMap<K, T>(maxEntries, 0.75f, true)

    fun seed(key: K, fallback: T): VaultV2SnapshotSeed<T> {
        return if (snapshots.containsKey(key)) {
            VaultV2SnapshotSeed(value = snapshots.getValue(key), hasSnapshot = true)
        } else {
            VaultV2SnapshotSeed(value = fallback, hasSnapshot = false)
        }
    }

    fun update(key: K, value: T) {
        snapshots[key] = value
        while (snapshots.size > maxEntries) {
            snapshots.remove(snapshots.entries.first().key)
        }
    }

    fun clear() {
        snapshots.clear()
    }
}

internal class VaultV2RetainedSourceSnapshotStore<K, S, T>(
    private val maxEntries: Int = 2
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private data class Entry<S, T>(
        val source: S,
        val value: T
    )

    private val snapshots = LinkedHashMap<K, Entry<S, T>>(maxEntries, 0.75f, true)

    fun seed(key: K, source: S, fallback: T): VaultV2SnapshotSeed<T> {
        val snapshot = snapshots[key]
        return if (snapshot != null && snapshot.source == source) {
            VaultV2SnapshotSeed(value = snapshot.value, hasSnapshot = true)
        } else {
            VaultV2SnapshotSeed(value = fallback, hasSnapshot = false)
        }
    }

    fun update(key: K, source: S, value: T) {
        snapshots[key] = Entry(source = source, value = value)
        while (snapshots.size > maxEntries) {
            snapshots.remove(snapshots.entries.first().key)
        }
    }

    fun clear() {
        snapshots.clear()
    }
}
