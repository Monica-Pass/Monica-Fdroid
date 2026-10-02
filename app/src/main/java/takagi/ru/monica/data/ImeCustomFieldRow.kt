package takagi.ru.monica.data

/** Keyboard buttons carry identifiers and labels only; values are read when explicitly filled. */
data class ImeCustomFieldRow(val id: Long, val label: String, val isProtected: Boolean)
