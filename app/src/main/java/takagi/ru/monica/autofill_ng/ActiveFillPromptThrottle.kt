package takagi.ru.monica.autofill_ng

internal class ActiveFillPromptThrottle(
    private val throttleMs: Long,
) {
    private val nextAllowedAtByPackage = mutableMapOf<String, Long>()

    @Synchronized
    fun tryAcquire(packageName: String, nowMs: Long): Boolean {
        val key = packageName.trim().lowercase()
        if (key.isEmpty()) return false

        val nextAllowedAt = nextAllowedAtByPackage[key] ?: Long.MIN_VALUE
        if (nowMs < nextAllowedAt) return false

        nextAllowedAtByPackage[key] = nowMs + throttleMs.coerceAtLeast(0L)
        if (nextAllowedAtByPackage.size > MAX_TRACKED_PACKAGES) {
            nextAllowedAtByPackage.entries.removeAll { (_, allowedAt) -> allowedAt <= nowMs }
            while (nextAllowedAtByPackage.size > MAX_TRACKED_PACKAGES) {
                val oldestKey = nextAllowedAtByPackage.minByOrNull { (_, allowedAt) -> allowedAt }?.key
                    ?: break
                nextAllowedAtByPackage.remove(oldestKey)
            }
        }
        return true
    }

    @Synchronized
    fun clear() {
        nextAllowedAtByPackage.clear()
    }

    private companion object {
        const val MAX_TRACKED_PACKAGES = 64
    }
}
