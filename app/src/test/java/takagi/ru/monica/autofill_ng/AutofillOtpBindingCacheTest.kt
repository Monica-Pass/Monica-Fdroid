package takagi.ru.monica.autofill_ng

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutofillOtpBindingCacheTest {
    @Test fun coldWaitTimeoutDoesNotRepeatOrCancelBackgroundScan() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = AutofillOtpBindingCache(scope)
            val gate = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val first = cache.getOrLoad { loads.incrementAndGet(); gate.await(); setOf(7L) }
            assertNull(withTimeoutOrNull(20) { first.await() })
            assertTrue(first.isActive)
            repeat(50) { assertSame(first, cache.getOrLoad { error("duplicate scan") }) }
            gate.complete(Unit)
            assertEquals(setOf(7L), first.await())
            assertEquals(1, loads.get())
            cache.invalidate()
            assertEquals(setOf(8L), cache.getOrLoad { loads.incrementAndGet(); setOf(8L) }.await())
            assertEquals(2, loads.get())
        } finally { scope.cancel() }
    }
}
