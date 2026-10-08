package takagi.ru.monica.data.model

/** Unknown/removed sections never hide live content or move it outside the sortable group. */
object WalletEditorOrder {
    fun resolve(saved: List<String>, available: List<String>): List<String> =
        (saved.filter { it in available } + available).distinct()

    fun move(keys: List<String>, from: String, to: String): List<String> {
        val source = keys.indexOf(from)
        val target = keys.indexOf(to)
        if (source < 0 || target < 0 || source == target) return keys
        return keys.toMutableList().apply { add(target, removeAt(source)) }
    }
}
