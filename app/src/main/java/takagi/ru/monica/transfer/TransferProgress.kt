package takagi.ru.monica.transfer

/** Counts describe the current phase, never an estimated timer. No credential data belongs here. */
enum class TransferPhase { READING, DECRYPTING, PREPARING, WRITING, ATTACHMENTS, PACKING, ENCRYPTING, SAVING }

data class TransferProgress(
    val phase: TransferPhase = TransferPhase.READING,
    val completed: Long = 0,
    val total: Long? = null,
    val bytes: Boolean = false,
) {
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let {
        (completed.toDouble() / it).toFloat().coerceIn(0f, 1f)
    }
}

fun interface TransferProgressReporter {
    fun report(progress: TransferProgress)

    companion object {
        val None = TransferProgressReporter { }
    }
}
