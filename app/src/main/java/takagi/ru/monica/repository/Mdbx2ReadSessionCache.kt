package takagi.ru.monica.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Callers serialize operations for each database. Borrowed sessions are never closed mid-read. */
internal class Mdbx2ReadSessionCache<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val close: (V) -> Unit,
    private val lifetimeMillis: Long = 30_000,
    private val capacity: Int = 4,
) {
    private class Entry<K, V>(val key: K, val value: V, val expiresAt: Long) {
        var expiry: Job? = null
    }

    private val monitor = Any()
    private val idle = linkedMapOf<Long, Entry<K, V>>()
    private var generation = 0L

    fun <T> use(
        databaseId: Long,
        key: K,
        canRetain: () -> Boolean,
        open: () -> V,
        keyAfterRead: () -> K?,
        remainingLifetimeMillis: ((V) -> Long)? = null,
        read: (V) -> T,
    ): T {
        val (startedGeneration, cached) = synchronized(monitor) {
            generation to idle.remove(databaseId)
        }
        cached?.expiry?.cancel()
        val reusable = try {
            cached?.takeIf { it.key == key && nowMillis() < it.expiresAt && canRetain() &&
                (remainingLifetimeMillis?.invoke(it.value) ?: 1L) > 0L }
        } catch (failure: Throwable) {
            cached?.let(::dispose)
            throw failure
        }
        if (cached != null && reusable == null) dispose(cached)
        val value = reusable?.value ?: open()
        val fixedExpiry = reusable?.expiresAt ?: (nowMillis() + lifetimeMillis)
        var succeeded = false
        try {
            return read(value).also { succeeded = true }
        } finally {
            val updatedKey = if (succeeded && canRetain()) runCatching(keyAfterRead).getOrNull() else null
            // Native policy owns the deadline. Metadata reads cannot renew authentication;
            // authorized activity may renew idle time, subject to the same absolute limit.
            val expiresAt = if (updatedKey != null && remainingLifetimeMillis != null) {
                val remaining = runCatching { remainingLifetimeMillis(value) }.getOrDefault(0L)
                nowMillis() + remaining.coerceAtLeast(0L)
            } else fixedExpiry
            var retained = false
            val evicted = mutableListOf<Entry<K, V>>()
            synchronized(monitor) {
                if (updatedKey != null && generation == startedGeneration &&
                    nowMillis() < expiresAt && canRetain()) {
                    val entry = Entry(updatedKey, value, expiresAt)
                    idle.put(databaseId, entry)?.let(evicted::add)
                    while (idle.size > capacity) idle.remove(idle.keys.first())?.let(evicted::add)
                    entry.expiry = scope.launch {
                        delay((expiresAt - nowMillis()).coerceAtLeast(0))
                        val expired = synchronized(monitor) {
                            if (idle[databaseId] === entry) idle.remove(databaseId) else null
                        }
                        expired?.let(::dispose)
                    }
                    retained = true
                }
            }
            evicted.forEach(::dispose)
            if (!retained) runCatching { close(value) }
        }
    }

    fun invalidate(databaseId: Long) {
        val removed = synchronized(monitor) {
            // Also prevents a read already in flight from repopulating invalidated state.
            generation++
            idle.remove(databaseId)
        }
        removed?.let(::dispose)
    }

    fun clear() {
        val removed = synchronized(monitor) {
            generation++
            idle.values.toList().also { idle.clear() }
        }
        removed.forEach(::dispose)
    }

    private fun dispose(entry: Entry<K, V>) {
        entry.expiry?.cancel()
        runCatching { close(entry.value) }
    }
}
