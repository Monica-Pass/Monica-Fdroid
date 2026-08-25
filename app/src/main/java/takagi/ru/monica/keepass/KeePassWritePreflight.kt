package takagi.ru.monica.keepass

internal data class KeePassWritePreflightResult(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val estimatedPeakBytes: Long,
    val availableHeapBytes: Long,
    val additionalBytesRequired: Long,
    val currentDatabaseBytes: Long,
    val incomingPayloadBytes: Long
)

/**
 * Estimates the simultaneous buffers held by kotpass decode, mutation and
 * encode. The policy intentionally runs before allocating an incoming
 * attachment or encoding a changed database.
 */
internal object KeePassWritePreflight {
    fun evaluate(
        currentDatabaseBytes: Long,
        incomingPayloadBytes: Long = 0L,
        availableHeapBytes: Long
    ): KeePassWritePreflightResult {
        require(currentDatabaseBytes >= 0L) { "Database size cannot be negative" }
        require(incomingPayloadBytes >= 0L) { "Incoming payload size cannot be negative" }
        require(availableHeapBytes > 0L) { "Available heap must be positive" }

        val estimatedPeak = saturatingAdd(
            BASE_OVERHEAD_BYTES,
            saturatingMultiply(currentDatabaseBytes, DATABASE_BUFFER_MULTIPLIER),
            saturatingMultiply(incomingPayloadBytes, PAYLOAD_BUFFER_MULTIPLIER)
        )
        val hardBudget = (availableHeapBytes * HARD_BUDGET_PERCENT) / 100L
        val warningBudget = (availableHeapBytes * WARNING_BUDGET_PERCENT) / 100L
        val allowed = estimatedPeak <= hardBudget
        return KeePassWritePreflightResult(
            allowed = allowed,
            requiresConfirmation = allowed && estimatedPeak >= warningBudget,
            estimatedPeakBytes = estimatedPeak,
            availableHeapBytes = availableHeapBytes,
            additionalBytesRequired = (estimatedPeak - hardBudget).coerceAtLeast(0L),
            currentDatabaseBytes = currentDatabaseBytes,
            incomingPayloadBytes = incomingPayloadBytes
        )
    }

    fun evaluateRuntime(
        currentDatabaseBytes: Long,
        incomingPayloadBytes: Long = 0L,
        runtime: Runtime = Runtime.getRuntime()
    ): KeePassWritePreflightResult {
        val used = runtime.totalMemory() - runtime.freeMemory()
        val available = (runtime.maxMemory() - used).coerceAtLeast(1L)
        return evaluate(currentDatabaseBytes, incomingPayloadBytes, available)
    }

    private fun saturatingMultiply(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier

    private fun saturatingAdd(vararg values: Long): Long {
        var result = 0L
        values.forEach { value ->
            if (Long.MAX_VALUE - result < value) return Long.MAX_VALUE
            result += value
        }
        return result
    }

    private const val MIB = 1024L * 1024L
    private const val BASE_OVERHEAD_BYTES = 16L * MIB
    private const val DATABASE_BUFFER_MULTIPLIER = 2L
    private const val PAYLOAD_BUFFER_MULTIPLIER = 3L
    private const val HARD_BUDGET_PERCENT = 90L
    private const val WARNING_BUDGET_PERCENT = 60L
}
