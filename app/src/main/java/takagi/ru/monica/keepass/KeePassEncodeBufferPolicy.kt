package takagi.ru.monica.keepass

internal object KeePassEncodeBufferPolicy {
    private const val DEFAULT_CAPACITY_BYTES = 32 * 1024
    private const val MAX_CAPACITY_BYTES = 16 * 1024 * 1024

    fun initialCapacity(estimatedSizeBytes: Long?): Int {
        return estimatedSizeBytes
            ?.coerceIn(DEFAULT_CAPACITY_BYTES.toLong(), MAX_CAPACITY_BYTES.toLong())
            ?.toInt()
            ?: DEFAULT_CAPACITY_BYTES
    }
}
