package takagi.ru.monica.data

/** Stable preference names; never persist enum ordinals. */
enum class VaultListSort {
    TITLE_ASC,
    TITLE_DESC,
    CREATED_DESC,
    CREATED_ASC,
    UPDATED_DESC,
    UPDATED_ASC;

    val isAlphabetical: Boolean get() = this == TITLE_ASC || this == TITLE_DESC
    val descending: Boolean get() = this == TITLE_DESC || this == CREATED_DESC || this == UPDATED_DESC

    companion object {
        fun fromStoredValue(value: String?): VaultListSort =
            entries.firstOrNull { it.name == value } ?: TITLE_ASC
    }
}
