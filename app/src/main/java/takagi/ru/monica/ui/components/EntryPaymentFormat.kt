package takagi.ru.monica.ui.components

/** Normalize only edits, never rewrite a stored value while opening an entry. */
object EntryPaymentFormat {
    fun cardNumber(input: String): String = input.filter { it in '0'..'9' }.take(19)
    fun cvv(input: String): String = input.filter { it in '0'..'9' }.take(4)
    fun expiry(input: String): String {
        val digits = input.filter { it in '0'..'9' }.take(6)
        return if (digits.length > 2) digits.take(2) + "/" + digits.drop(2) else digits
    }
    fun joinExpiry(month: String, year: String): String = when {
        year.isEmpty() -> month
        else -> "$month/$year"
    }
    fun splitExpiry(value: String): Pair<String, String> =
        if ('/' in value) value.substringBefore('/') to value.substringAfter('/') else value to ""
}
