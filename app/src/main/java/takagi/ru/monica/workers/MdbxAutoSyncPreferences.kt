package takagi.ru.monica.workers

import android.content.Context

/** Device-local scheduling preferences. No secrets or vault format changes. */
class MdbxAutoSyncPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mdbx_auto_sync", Context.MODE_PRIVATE)

    fun isEnabled(id: Long): Boolean = prefs.getBoolean("enabled_$id", false)

    fun setEnabled(id: Long, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled)
            .remove("next_$id").remove("idle_$id").apply()
    }

    fun resetIdle(id: Long) {
        prefs.edit().remove("next_$id").remove("idle_$id").apply()
    }

    fun isPollDue(id: Long, now: Long = System.currentTimeMillis()): Boolean {
        val next = prefs.getLong("next_$id", 0)
        // A wall-clock rollback must not suppress polling indefinitely.
        return next <= now || next - now > MAX_INTERVAL_MS
    }

    fun recordSuccess(id: Long, changed: Boolean, now: Long = System.currentTimeMillis()) {
        val idle = if (changed) 0 else (prefs.getInt("idle_$id", 0) + 1).coerceAtMost(4)
        prefs.edit().putInt("idle_$id", idle)
            .putLong("next_$id", now + intervalForIdleCount(idle)).apply()
    }

    companion object {
        const val MAX_INTERVAL_MS = 300_000L
        internal fun intervalForIdleCount(count: Int): Long =
            (60_000L * (1L shl (count - 1).coerceIn(0, 3))).coerceAtMost(MAX_INTERVAL_MS)
    }
}
