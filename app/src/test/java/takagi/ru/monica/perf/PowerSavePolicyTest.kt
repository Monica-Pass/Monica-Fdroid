package takagi.ru.monica.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavePolicyTest {
    @Test
    fun normalModeKeepsDecorativeAndBackgroundWorkEnabled() {
        val policy = PowerSavePolicy.fromPowerSaveMode(false)
        assertFalse(policy.isPowerSaveMode)
        assertTrue(policy.allowContinuousDecorativeMotion)
        assertTrue(policy.allowBackgroundPrewarm)
        assertFalse(policy.useReducedDecorativeElevation)
    }

    @Test
    fun powerSaveOnlyDisablesNonEssentialWork() {
        val policy = PowerSavePolicy.fromPowerSaveMode(true)
        assertTrue(policy.isPowerSaveMode)
        assertFalse(policy.allowContinuousDecorativeMotion)
        assertFalse(policy.allowBackgroundPrewarm)
        assertTrue(policy.useReducedDecorativeElevation)
    }
}
