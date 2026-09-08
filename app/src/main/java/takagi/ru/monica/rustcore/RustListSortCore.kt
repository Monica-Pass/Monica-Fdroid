package takagi.ru.monica.rustcore

import takagi.ru.monica.data.SecureItem

/** One numeric batch per snapshot. Card numbers, OTP keys and itemData never cross JNI. */
object RustListSortCore {
    private val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("monica_rust_jni")
            nativeSortIndices(longArrayOf(1, 0, 0, 1, 0), true)?.contentEquals(intArrayOf(0)) == true
        }.getOrDefault(false)
    }

    fun <T> sort(items: List<T>, tieById: Boolean, itemOf: (T) -> SecureItem): List<T> {
        if (items.size < 256) return kotlinSort(items, tieById, itemOf)
        return nativeSort(items, tieById, itemOf) ?: kotlinSort(items, tieById, itemOf)
    }

    internal fun <T> nativeSort(items: List<T>, tieById: Boolean, itemOf: (T) -> SecureItem): List<T>? {
        if (!available || items.size > (Int.MAX_VALUE - 1) / 4) return null
        val batch = LongArray(1 + items.size * 4)
        batch[0] = 1
        items.forEachIndexed { index, value ->
            val item = itemOf(value)
            val offset = 1 + index * 4
            batch[offset] = if (item.isFavorite) 1 else 0
            batch[offset + 1] = item.sortOrder.toLong()
            batch[offset + 2] = item.id
            batch[offset + 3] = item.updatedAt.time
        }
        val indices = runCatching { nativeSortIndices(batch, tieById) }.getOrNull() ?: return null
        if (indices.size != items.size) return null
        val seen = BooleanArray(items.size)
        for (index in indices) {
            if (index !in items.indices || seen[index]) return null
            seen[index] = true
        }
        return indices.map { items[it] }
    }

    internal fun <T> kotlinSort(items: List<T>, tieById: Boolean, itemOf: (T) -> SecureItem): List<T> =
        items.sortedWith(
            compareByDescending<T> { itemOf(it).isFavorite }
                .thenBy { itemOf(it).sortOrder }
                .thenBy { if (tieById) itemOf(it).id else 0L }
                .thenByDescending { itemOf(it).updatedAt.time }
        )

    @JvmStatic
    private external fun nativeSortIndices(metadata: LongArray, tieById: Boolean): IntArray?
}
