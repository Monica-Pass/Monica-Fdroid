package takagi.ru.monica.rustcore

import takagi.ru.monica.data.WalletStack

/** One background batch per wallet snapshot; only numeric card IDs cross JNI. */
internal object RustWalletStackCore {
    private val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("monica_rust_jni")
            nativeProjectIndices(longArrayOf(1, 2, 1, 7, 9, 2, 9, 7), false)
                ?.contentEquals(intArrayOf(1, 1, 0, 2, 1, 0, 0)) == true
        }.getOrDefault(false)
    }

    fun <T> projectIndices(
        cards: List<T>,
        stacks: List<WalletStack>,
        selectionMode: Boolean,
        idOf: (T) -> Long
    ): IntArray? {
        if (!available) return null
        val length = 3L + cards.size + stacks.size + stacks.sumOf { it.memberIds.size.toLong() }
        if (length > Int.MAX_VALUE) return null
        // Stack IDs stay in Kotlin. Conflicting legacy snapshots use the fallback.
        val stackIds = HashSet<String>(stacks.size)
        if (stacks.any { !stackIds.add(it.id) }) return null
        val batch = LongArray(length.toInt())
        batch[0] = 1
        batch[1] = cards.size.toLong()
        batch[2] = stacks.size.toLong()
        var cursor = 3
        cards.forEach { batch[cursor++] = idOf(it) }
        stacks.forEach { stack ->
            batch[cursor++] = stack.memberIds.size.toLong()
            stack.memberIds.forEach { batch[cursor++] = it }
        }
        return runCatching { nativeProjectIndices(batch, selectionMode) }.getOrNull()
    }

    @JvmStatic
    private external fun nativeProjectIndices(metadata: LongArray, selectionMode: Boolean): IntArray?
}
