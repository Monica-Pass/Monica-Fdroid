package takagi.ru.monica.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceScaleTest {
    @Test
    fun storedScaleValuesAreNormalizedToSafeRange() {
        assertEquals(InterfaceScale.DEFAULT_PERCENT, InterfaceScale.normalizePercent(null))
        assertEquals(InterfaceScale.MIN_PERCENT, InterfaceScale.normalizePercent(20))
        assertEquals(93, InterfaceScale.normalizePercent(93))
        assertEquals(InterfaceScale.MAX_PERCENT, InterfaceScale.normalizePercent(180))
    }

    @Test
    fun densityScalingPreservesDefaultAndAppliesBoundsPredictably() {
        assertEquals(3f, InterfaceScale.calculateDensity(3f, 100), 0.0001f)
        assertEquals(2.4f, InterfaceScale.calculateDensity(3f, 80), 0.0001f)
        assertEquals(3.6f, InterfaceScale.calculateDensity(3f, 120), 0.0001f)
        assertEquals(2.4f, InterfaceScale.calculateDensity(3f, 20), 0.0001f)
    }

    @Test
    fun effectiveDpiMatchesTheAppliedDensityScale() {
        assertEquals(440, InterfaceScale.calculateEffectiveDpi(440, 100))
        assertEquals(352, InterfaceScale.calculateEffectiveDpi(440, 80))
        assertEquals(528, InterfaceScale.calculateEffectiveDpi(440, 120))
    }
}
