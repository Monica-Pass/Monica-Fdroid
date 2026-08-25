package takagi.ru.monica.autofill_ng

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveFillPromptThrottleTest {
    @Test
    fun throttlesHitsAndMissesPerPackageBeforeWorkStarts() {
        val throttle = ActiveFillPromptThrottle(throttleMs = 5_000L)

        assertTrue(throttle.tryAcquire("com.example.first", nowMs = 1_000L))
        assertFalse(throttle.tryAcquire("com.example.first", nowMs = 5_999L))
        assertTrue(throttle.tryAcquire("com.example.second", nowMs = 2_000L))
        assertTrue(throttle.tryAcquire("COM.EXAMPLE.FIRST", nowMs = 6_000L))
    }

    @Test
    fun clearAllowsAnImmediateFreshPrompt() {
        val throttle = ActiveFillPromptThrottle(throttleMs = 5_000L)

        assertTrue(throttle.tryAcquire("com.example", nowMs = 1_000L))
        throttle.clear()
        assertTrue(throttle.tryAcquire("com.example", nowMs = 1_001L))
        assertFalse(throttle.tryAcquire("  ", nowMs = 1_002L))
    }
}
