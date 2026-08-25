package takagi.ru.monica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpCountdownVibrationGateTest {

    @Test
    fun multipleExpiringCardsProduceOnlyOnePulsePerWallClockSecond() {
        val gate = TotpCountdownVibrationGate()

        assertTrue(gate.shouldVibrate(epochSecond = 100, remainingSeconds = listOf(5, 5, 5)))
        assertFalse(gate.shouldVibrate(epochSecond = 100, remainingSeconds = listOf(5, 4, 3)))
        assertTrue(gate.shouldVibrate(epochSecond = 101, remainingSeconds = listOf(4, 4)))
    }

    @Test
    fun nonExpiringAndHotpOnlyPagesDoNotVibrate() {
        val gate = TotpCountdownVibrationGate()

        assertFalse(gate.shouldVibrate(epochSecond = 200, remainingSeconds = emptyList()))
        assertFalse(gate.shouldVibrate(epochSecond = 201, remainingSeconds = listOf(6, 17, 30)))
        assertFalse(gate.shouldVibrate(epochSecond = 202, remainingSeconds = listOf(0)))
    }

    @Test
    fun skippedNonExpiringSecondDoesNotConsumeTheNextExpiringPulse() {
        val gate = TotpCountdownVibrationGate()

        assertFalse(gate.shouldVibrate(epochSecond = 300, remainingSeconds = listOf(8)))
        assertTrue(gate.shouldVibrate(epochSecond = 300, remainingSeconds = listOf(5)))
    }
}
